#!/usr/bin/env groovy
@Grab('org.yaml:snakeyaml:2.4')

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

import groovy.io.FileType
import groovy.json.JsonOutput
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.xml.XmlSlurper

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.events.AliasEvent
import org.yaml.snakeyaml.events.CollectionStartEvent
import org.yaml.snakeyaml.events.NodeEvent
import org.yaml.snakeyaml.events.ScalarEvent

/**
 * Represents a software version with major, minor and optional patch and modifier.
 */
@CompileStatic
class Version implements Comparable<Version> {

    String versionText
    int major
    int minor
    int patch
    VersionModifier modifier

    // Matches: 1.2.3, 1.2, 1.2.3-RC1, 1.2.3-M1, 1.2.3.RC1, 1.2.3-SNAPSHOT etc.
    private static final Pattern VERSION_PATTERN = ~/^(\d+)\.(\d+)(?:\.(\d+))?(?:[.-](.+))?$/

    /**
     * Builds a Version instance from a version string.
     *
     * @param versionText The version string to parse.
     * @return A Version instance.
     * @throws IllegalArgumentException if the version string is null or invalid.
     */
    static Version build(String versionText) {
        if (versionText == null) {
            throw new IllegalArgumentException('Version string cannot be null')
        }

        def matcher = VERSION_PATTERN.matcher(versionText)
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid version format: $versionText")
        }

        def patchGroup = matcher.group(3) // allowed to be be null
        def version = new Version(
                versionText: versionText,
                major: matcher.group(1).toInteger(),
                minor: matcher.group(2).toInteger(),
                patch: patchGroup ? matcher.group(3).toInteger() : 0
        )

        def modifierPart = matcher.group(4) as String
        if (modifierPart && VersionModifier.isModifier(modifierPart)) {
            version.modifier = new VersionModifier(modifierPart)
        }

        return version
    }

    boolean isSnapshot() {
        // There are some strange versions out there that don't use standard SNAPSHOT modifier
        modifier?.snapshot || versionText.containsIgnoreCase('SNAPSHOT')
    }

    boolean hasModifier() {
        modifier != null
    }

    @Override
    int compareTo(Version o) {
        int cmp = this.major <=> o.major
        if (cmp != 0) return cmp

        cmp = this.minor <=> o.minor
        if (cmp != 0) return cmp

        cmp = this.patch <=> o.patch
        if (cmp != 0) return cmp

        // Same major.minor.patch → look at qualifier
        if (this.hasModifier() && !o.hasModifier()) {
            return -1
        } else if (!this.hasModifier() && o.hasModifier()) {
            return 1
        } else if (this.hasModifier() && o.hasModifier()) {
            return this.modifier <=> o.modifier
        }

        // Fallback to string comparison if we end up here
        return this.versionText <=> o.versionText
    }

    @Override
    String toString() {
        versionText
    }

    @CompileStatic
    static class VersionModifier implements Comparable<VersionModifier> {

        private String modifierText

        VersionModifier(String modifierText) {
            this.modifierText = modifierText
        }

        static boolean isModifier(String s) {
            if (!s) return false
            s == 'SNAPSHOT' ||
                    s == 'BUILD-SNAPSHOT' ||
                    (s ==~ /M\d+/) ||
                    (s ==~ /RC\d+/) ||
                    (s ==~ /ALPHA\d+/) ||
                    (s ==~ /BETA\d+/)
        }

        int getReleaseCandidateVersion() {
            modifierText.replace('RC', '').toInteger()
        }

        int getMilestoneVersion() {
            modifierText.replace('M', '').toInteger()
        }

        int getAlphaVersion() {
            modifierText.replace('ALPHA', '').toInteger()
        }

        int getBetaVersion() {
            modifierText.replace('BETA', '').toInteger()
        }

        boolean isSnapshot() {
            modifierText in ['BUILD-SNAPSHOT', 'SNAPSHOT']
        }

        boolean isReleaseCandidate() {
            modifierText.toUpperCase().startsWith('RC')
        }

        boolean isMilestone() {
            modifierText.toUpperCase().startsWith('M')
        }

        boolean isAlpha() {
            modifierText.toUpperCase().startsWith('ALPHA')
        }

        boolean isBeta() {
            modifierText.toUpperCase().startsWith('BETA')
        }

        /**
         * Main type rank:
         * 0 = generic snapshot (SNAPSHOT / BUILD-SNAPSHOT)
         * 1 = milestone (M1, M2, ...)
         * 2 = release candidate (RC1, RC2, ...)
         * 3 = final (no qualifier) – handled in Version, not here
         */
        int getTypeRank() {
            if (snapshot)         return 0
            if (alpha)            return 1
            if (beta)             return 2
            if (milestone)        return 3
            if (releaseCandidate) return 4
            return 0
        }

        @Override
        int compareTo(VersionModifier o) {
            int rankCmp = typeRank <=> o.typeRank
            if (rankCmp != 0) {
                return rankCmp
            }

            // Same type: compare numeric part if applicable
            if (releaseCandidate && o.releaseCandidate) {
                return releaseCandidateVersion <=> o.releaseCandidateVersion
            }
            if (milestone && o.milestone) {
                return milestoneVersion <=> o.milestoneVersion
            }
            if (beta && o.beta) {
                return betaVersion <=> o.betaVersion
            }
            if (alpha && o.alpha) {
                return alphaVersion <=> o.alphaVersion
            }

            return 0
        }

        @Override
        String toString() {
            modifierText
        }
    }
}

@CompileStatic
class Utils {

    static final String USER_AGENT = 'Grails Plugin Version Update Checker'

    /**
     * Opens an HttpURLConnection to the given URL with the specified request method,
     * executes the provided action closure, and ensures the connection is closed afterwards.
     *
     * @param url The URL to connect to.
     * @param requestMethod The HTTP request method (e.g., 'GET', 'HEAD').
     * @param action A closure that takes the HttpURLConnection as delegate to perform actions.
     * @return The result of the action closure.
     */
    static <T> T doWithConnection(
            String url,
            String requestMethod,
            @DelegatesTo(
                    strategy = Closure.DELEGATE_FIRST,
                    value = HttpURLConnection
            )
            Closure<T> action
    ) {
        def conn = (new URL(url).openConnection() as HttpURLConnection).tap {
            it.requestMethod = requestMethod
            it.connectTimeout = 20000
            it.readTimeout = 20000
            it.setRequestProperty('User-Agent', USER_AGENT)
        }
        try {
            conn.connect()
            action.delegate = conn
            action.resolveStrategy = Closure.DELEGATE_FIRST
            return action.call()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Fetches the Last-Modified header of an artifact URL via HEAD request.
     *
     * Returns ISO-8601 date string or null if not found.
     */
    static Date fetchArtifactLastModified(String url) {
        doWithConnection(url, 'HEAD') {
            long ts = lastModified   // 0 if header missing
            if (ts > 0L) {
                return new Date(ts)
            }
            return null
        }
    }

    /**
     * Constructs the maven-metadata.xml URL from mavenRepo and coords.
     */
    static String createMetadataUrl(String mavenRepo, String coords) {
        // Ensure mavenRepo does not end with '/'
        if (mavenRepo.endsWith('/')) {
            mavenRepo = mavenRepo.substring(0, mavenRepo.length() - 1)
        }
        // Convert groupId to path
        def artefactPath = coords.replaceAll(/[.:]/, '/')
        "$mavenRepo/$artefactPath/maven-metadata.xml"
    }

    /**
     * Constructs the artifact JAR URL from mavenRepo, coords, and version.
     */
    static String createArtifactUrl(String mavenRepo, String coords, Version version) {
        // Ensure mavenRepo does not end with '/'
        if (mavenRepo.endsWith('/')) {
            mavenRepo = mavenRepo.substring(0, mavenRepo.length() - 1)
        }
        // Convert groupId to path
        def parts = coords.split(':')
        def groupId = parts[0]
        def artifactId = parts[1]
        def groupPath = groupId.replaceAll(/[.:]/, '/')
        "$mavenRepo/$groupPath/$artifactId/$version/$artifactId-${version}.jar"
    }

    /**
     * Fetches artifact version info (e.g. last modified date) from Maven repo.
     *
     * Returns [ 'date': String ] or [:] on failure.
     */
    static Map<String, Object> fetchArtifactVersionInfo(Map<String, Object> pluginInfo, Version version) {
        def mavenRepo = pluginInfo['maven-repo'] as String
        def coords = pluginInfo['coords'] as String
        if (!mavenRepo || !coords || !version) {
            return [:]
        }
        def artifactUrl = createArtifactUrl(mavenRepo, coords, version)

        Date lastModified = null
        String grailsCompatibility = null
        Map<String, Object> forgePack = null
        def candidateUrls = [
                artifactUrl,
                artifactUrl.replaceFirst(/\.jar$/, '-plain.jar'),
                artifactUrl.replaceFirst(/\.jar$/, '.zip') // Grails 2
        ]
        // Fetch Last-Modified header
        for (url in candidateUrls) {
            try {
                lastModified = fetchArtifactLastModified(url)
                if (lastModified) {
                    break
                }
            } catch(FileNotFoundException ignore) {
                // Try next
            }
        }

        // Try the candidate urls in order
        for (url in candidateUrls) {
            try {
                def artifactInfo = readPluginArtifact(url)
                grailsCompatibility = artifactInfo.grailsVersion as String
                // A Forge feature pack is only ever read from the main jar of the plugin
                if (url == artifactUrl) {
                    forgePack = artifactInfo.pack as Map<String, Object>
                }
                if (grailsCompatibility || forgePack) {
                    break
                }
            } catch(FileNotFoundException ignore) {
                // Try next
            }
        }

        if (!grailsCompatibility && !forgePack) {
            System.err.println("WARNING: Could not find plugin artifact for $coords:$version")
        }

        def versionInfo = [:] as Map<String, Object>
        if (lastModified) {
            versionInfo.put('date', lastModified as Date)
        }
        if (grailsCompatibility) {
            versionInfo.put('grailsVersion', grailsCompatibility)
        }
        if (forgePack) {
            versionInfo.put('pack', forgePack)
        }

        return versionInfo
    }

    /** The descriptor of a Forge feature pack inside the main jar of a plugin. */
    static final String FORGE_PACK_DESCRIPTOR = 'META-INF/grails-forge/feature.yml'

    /** The same limits Forge applies when it reads the descriptor. */
    static final int FORGE_PACK_MAX_LENGTH = 256 * 1024
    static final int FORGE_PACK_MAX_DEPTH = 20

    private static final Pattern FORGE_PACK_NAME = ~/[a-z][a-z0-9]*(-[a-z0-9]+)*/
    private static final Pattern FORGE_PACK_RANGE = ~/[A-Za-z0-9_.+\-\[\](), ]{1,200}/
    private static final List<String> FORGE_PACK_KINDS = ['feature', 'preset']
    /** The types of application Grails 9 creates; a type that is not among them is recorded with a warning (see forgePackApplicationTypes). */
    private static final List<String> FORGE_PACK_APPLICATION_TYPES = ['web', 'rest-api', 'web-plugin', 'plugin', 'rest-api-plugin']
    private static final int FORGE_PACK_MAX_APPLICATION_TYPES = 20

    /**
     * Reads the Forge feature pack of a plugin jar, if it has one, as the `pack` member of a version.
     *
     * The descriptor is read as data only (SnakeYAML's SafeConstructor, no aliases, anchors or tags,
     * bounded in size and depth) and nothing of the jar is executed. Only the members the Forge CLI
     * needs to choose a version without downloading the jar are copied: `format`, `kind`, `name`,
     * `templateApi`, `applicationTypes` (when declared; for a preset, the one `applicationType` it is
     * for) and `compatibility.grails`/`compatibility.forge`, plus the SHA-256 of the jar itself, which
     * the CLI holds the jar to when it downloads it.
     *
     * @return the `pack` member, or null if the jar has no pack or its descriptor cannot be used
     */
    @CompileDynamic
    static Map<String, Object> readForgePack(ZipFile zip, File jar, String artifactUrl) {
        ZipEntry entry = zip.getEntry(FORGE_PACK_DESCRIPTOR)
        if (!entry) {
            return null
        }
        try {
            byte[] bytes = null
            zip.getInputStream(entry).withCloseable { is ->
                bytes = is.readNBytes(FORGE_PACK_MAX_LENGTH + 1)
            }
            if (bytes.length > FORGE_PACK_MAX_LENGTH) {
                throw new IllegalArgumentException("is larger than $FORGE_PACK_MAX_LENGTH bytes")
            }
            def text = new String(bytes, StandardCharsets.UTF_8)
            def options = new LoaderOptions().tap {
                it.allowDuplicateKeys = false
                it.maxAliasesForCollections = 0
                it.allowRecursiveKeys = false
                it.nestingDepthLimit = FORGE_PACK_MAX_DEPTH + 1
                it.codePointLimit = FORGE_PACK_MAX_LENGTH
            }
            def yaml = new Yaml(new SafeConstructor(options))
            for (event in yaml.parse(new StringReader(text))) {
                if (event instanceof AliasEvent || (event instanceof NodeEvent && event.anchor != null)) {
                    throw new IllegalArgumentException('uses an anchor or an alias')
                }
                if ((event instanceof ScalarEvent || event instanceof CollectionStartEvent) && event.tag != null) {
                    throw new IllegalArgumentException('uses a tag')
                }
            }
            def descriptor = new Yaml(new SafeConstructor(options)).load(text)
            if (!(descriptor instanceof Map)) {
                throw new IllegalArgumentException('is not a mapping')
            }
            def pack = [:] as Map<String, Object>
            ['format', 'templateApi'].each { key ->
                def value = descriptor[key]
                if (!(value instanceof Integer) || value < 1) {
                    throw new IllegalArgumentException("has no whole number for '$key'")
                }
            }
            if (!(descriptor.kind in FORGE_PACK_KINDS)) {
                throw new IllegalArgumentException("has a 'kind' that is not one of $FORGE_PACK_KINDS")
            }
            if (!(descriptor.name instanceof String) || descriptor.name.length() > 100 || !FORGE_PACK_NAME.matcher(descriptor.name).matches()) {
                throw new IllegalArgumentException("has a 'name' that is not the name of a feature")
            }
            pack.format = descriptor.format
            pack.kind = descriptor.kind
            pack.name = descriptor.name
            pack.templateApi = descriptor.templateApi
            def types = forgePackApplicationTypes(descriptor, artifactUrl)
            if (types != null) {
                pack.applicationTypes = types
            }
            def compatibility = descriptor.compatibility
            if (!(compatibility instanceof Map)) {
                throw new IllegalArgumentException("has no 'compatibility'")
            }
            pack.compatibility = ['grails', 'forge'].collectEntries { key ->
                def range = compatibility[key]
                if (!(range instanceof String) || !FORGE_PACK_RANGE.matcher(range).matches()) {
                    throw new IllegalArgumentException("has no version range for 'compatibility.$key'")
                }
                [(key): range]
            }
            def digest = MessageDigest.getInstance('SHA-256')
            jar.withInputStream { is ->
                byte[] buffer = new byte[64 * 1024]
                int read
                while ((read = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            pack.sha256 = digest.digest().encodeHex().toString()
            return pack
        }
        catch(Exception ex) {
            // One jar with a pack that cannot be used must never fail the index
            System.err.println("WARNING: Ignoring the Forge feature pack of $artifactUrl: $FORGE_PACK_DESCRIPTOR ${ex.message ?: ex.class.simpleName}")
            return null
        }
    }

    /**
     * The `applicationTypes` of the `pack` member for a descriptor: the types of application the pack
     * is for, each once and in the order of the descriptor.
     *
     * A feature names them in `applicationTypes`, a list, and is for every type when it has no such
     * key, which is recorded by leaving `applicationTypes` out. A preset is for one type, which it
     * names in `applicationType`, and that one is recorded as the only entry of `applicationTypes`,
     * so that a reader of the index finds the types of every kind of pack under one key.
     *
     * A name that has the form of a type but is not one this indexer knows does not cost the pack its
     * entry, and with it the types it is validly for: the name is recorded as it is, which a reader
     * passes over when it does not know the type either, and a warning says so. Only a value that
     * cannot be a list of names of types is refused.
     *
     * @return the types, or null for a feature that is for every type
     * @throws IllegalArgumentException if the descriptor does not say which types it is for in the form its kind has
     */
    @CompileDynamic
    static List<String> forgePackApplicationTypes(Map descriptor, String artifactUrl) {
        def key = 'applicationTypes'
        def names
        if (descriptor.kind == 'preset') {
            key = 'applicationType'
            def type = descriptor[key]
            if (!(type instanceof String)) {
                throw new IllegalArgumentException("is a preset that has no '$key', the name of the one type of application it is for")
            }
            names = [type]
        }
        else if (!descriptor.containsKey(key)) {
            return null
        }
        else {
            names = descriptor[key]
            if (!(names instanceof List) || !names || names.size() > FORGE_PACK_MAX_APPLICATION_TYPES) {
                throw new IllegalArgumentException("has '$key' that are not a list of names of types of application, such as $FORGE_PACK_APPLICATION_TYPES")
            }
        }
        if (!names.every { it instanceof String && it.length() <= 100 && FORGE_PACK_NAME.matcher(it).matches() }) {
            throw new IllegalArgumentException("names in '$key' what cannot be the name of a type of application, such as $FORGE_PACK_APPLICATION_TYPES")
        }
        def unknown = names.findAll { !(it in FORGE_PACK_APPLICATION_TYPES) }.unique(false)
        if (unknown) {
            System.err.println("WARNING: The Forge feature pack of $artifactUrl names in '$key' $unknown, which is not among the types of " +
                    "application this indexer knows, $FORGE_PACK_APPLICATION_TYPES. The pack is recorded with every type it names.")
        }
        return names.unique(false)
    }

    /**
     * Extracts the grailsVersion compatibility string from a Grails plugin JAR.
     *
     * Looks for META-INF/grails-plugin.xml and reads either:
     *   - <plugin grailsVersion='...'>
     *   - or <grailsVersion>...</grailsVersion>
     *
     * @param jarFile the plugin JAR file
     * @return the grailsVersion string (e.g. "6.0.0 > *"), or null if not found
     */
    static String extractGrailsVersionFromPluginJar(String artifactUrl) throws FileNotFoundException {
        readPluginArtifact(artifactUrl).grailsVersion as String
    }

    /**
     * Downloads a plugin artifact once and reads what the index records of it: the grailsVersion of
     * its plugin descriptor and, if it has one, its Forge feature pack (see readForgePack).
     *
     * @return a map with the keys 'grailsVersion' and 'pack', each null if not found
     */
    @CompileDynamic
    static Map<String, Object> readPluginArtifact(String artifactUrl) throws FileNotFoundException {
        def file = File.createTempFile('plugin-', '.jar').tap {
            it.deleteOnExit()
        }

        println("Downloading plugin artifact from $artifactUrl to extract grailsVersion and Forge feature pack...")
        doWithConnection(artifactUrl, 'GET') {
            inputStream.withCloseable { is ->
                file.withOutputStream { os ->
                    os << is
                }
            }
        }

        if (!file || !file.exists()) {
            return [:]
        }

        ZipFile zip = null
        try {
            zip = new ZipFile(file)
            def pack = readForgePack(zip, file, artifactUrl)
            return [grailsVersion: readGrailsVersion(zip), pack: pack]
        }
        catch(ZipException ex) {
            System.err.println("Invalid Zip file at $artifactUrl : ${ex.message}")
            return [:]
        }
        finally {
            if (zip != null) {
                zip.close()
            }
            file.delete()
        }
    }

    @CompileDynamic
    private static String readGrailsVersion(ZipFile zip) {
            def candidates = [
                    'META-INF/grails-plugin.xml',
                    'plugin.xml' // Grails 2
            ]
            def entry = candidates.findResult {
                zip.getEntry(it)
            }

            if (!entry) {
                return null
            }

            def xml = null
            zip.getInputStream(entry).withCloseable { is ->
                xml = new XmlSlurper().parse(is)
            }

            if (!xml) {
                return null
            }

            // 1) Try attribute on <plugin ... grailsVersion='...'>
            String attrVersion = xml.@grailsVersion?.toString()
            if (attrVersion) {
                return attrVersion
            }

            // 2) Fallback: <grailsVersion>...</grailsVersion>
            String elemVersion = xml.grailsVersion?.text()
            if (elemVersion) {
                return elemVersion
            }

            return null
    }

    /**
     * Adds the `pack` member to versions that are already in the YAML file and have none.
     *
     * A version already present is never updated by the normal run, so a plugin that gained a Forge
     * feature pack before this indexer recorded packs would never get one. This downloads the main jar
     * of each such version once (honouring the version's own `coords` and `maven-repo`) and changes
     * nothing but `pack`. It is only done when the script is run with --rescan-packs.
     */
    static void rescanForgePacks(Map<String, Object> pluginInfo) {
        (pluginInfo.get('versions') ?: [] as List<Map>).each { Map v ->
            if (v.containsKey('pack') || v.artifactAvailable == false) {
                return
            }
            def mavenRepo = (v['maven-repo'] ?: pluginInfo['maven-repo']) as String
            def coords = (v.coords ?: pluginInfo.coords) as String
            if (!mavenRepo || !coords) {
                return
            }
            try {
                def version = Version.build(v.version as String)
                def pack = readPluginArtifact(createArtifactUrl(mavenRepo, coords, version)).pack
                if (pack) {
                    v.pack = pack
                }
            }
            catch(Exception ex) {
                System.err.println("INFO: No Forge feature pack read for $coords:${v.version}: ${ex.message ?: ex.class.simpleName}")
            }
        }
    }

    /**
     * Fetches all available versions from maven-metadata.xml.
     *
     * Returns List<Version> or [] on failure.
     */
    @CompileDynamic
    static List<Version> fetchPluginVersions(Map<String, Object> pluginInfo) {
        def mavenRepo = pluginInfo['maven-repo'] as String
        def coords = pluginInfo['coords'] as String
        if (!mavenRepo || !coords) {
            return []
        }
        def metadataUrl = createMetadataUrl(mavenRepo, coords)

        doWithConnection(metadataUrl, 'GET') {
            int code = responseCode
            if (code != 200) {
                System.err.println("WARNING: $metadataUrl -> HTTP $code")
                return []
            }
            def xml = new XmlSlurper().parse(inputStream)
            (xml.versioning.versions.version*.text() as List<String>)
                    .collect {
                        try { Version.build(it) }
                        catch(IllegalArgumentException ex) {
                            System.err.println("INFO: Skipping invalid version '$it' for $coords: $ex.message")
                            return null
                        }
                    }
                    .findAll { it && !it.snapshot }
        }
    }

    /**
     * Creates a Yaml dumper with standard options.
     */
    static Yaml createYamlDumper() {
        def options = new DumperOptions().tap {
            it.defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            it.defaultScalarStyle = DumperOptions.ScalarStyle.PLAIN
            it.indicatorIndent = 2
            it.indentWithIndicator = true
            it.width = 100
        }
        new Yaml(options)
    }

    static Map processPluginFile(File f, boolean rescanPacks = false) {
        if (!(f.name.endsWith('.yml') || f.name.endsWith('.yaml'))) {
            System.err.println("INFO: Skipping non-YAML file: $f.path")
            return null
        }
        println("Processing plugin file: $f.path")
        def yaml = new Yaml()
        Map<String, Object> pluginInfo = null
        f.withInputStream {
            pluginInfo = yaml.load(it)
        }
        if (!pluginInfo) {
            System.err.println("WARNING: File $f.path could not be parsed as YAML, skipping")
            return null
        }

        // Expect at least 'coords'
        def coords = pluginInfo.coords as String
        if (!coords) {
            System.err.println("WARNING: File $f.path has no 'coords', skipping")
            return null
        }

        // Coords format: groupId:artifactId[:version]
        def parts = coords.split(':')
        if (parts.length < 2) {
            System.err.println("WARNING: Invalid coords '$coords' in $f.path, skipping")
            return null
        }

        def mavenRepo = pluginInfo['maven-repo'] as String
        if (!mavenRepo) {
            // No mavenRepo -> can't sync versions, but still include in index
            System.err.println("INFO: No 'maven-repo' for $coords in $f.path, not syncing versions")
        } else {
            // Fetch and update latest version info
            def existingVersions = pluginInfo.get('versions', []) as List<Map>
            def availableVersions = [] as List<Version>
            try {
                availableVersions = fetchPluginVersions(pluginInfo)
            }
            catch(Exception e) {
                System.err.println(
                        "WARNING: Could not fetch versions for $coords: ${e.message}. Keeping existing versions."
                )
            }
            availableVersions.each { version ->
                def alreadyPresent = existingVersions.any { v -> v.version == version.versionText }
                if (alreadyPresent) {
                    return
                }

                def versionInfo = fetchArtifactVersionInfo(pluginInfo, version)
                if (!versionInfo) {
                    System.err.println("WARNING: Could not fetch info for version $version of $coords")
                    return
                }

                def entry = [
                        version: version.versionText,
                        date: versionInfo.date,
                        grailsVersion: versionInfo.grailsVersion,
                        pack: versionInfo.pack
                ].findAll { k, v -> v != null }

                existingVersions << entry
            }

            pluginInfo.versions = existingVersions.sort { a, b ->
                def verA = Version.build(a.version as String)
                def verB = Version.build(b.version as String)
                return verB <=> verA
            }
        }

        if (rescanPacks) {
            rescanForgePacks(pluginInfo)
        }

        // Restore the date types (YAML parser converts to String)
        (pluginInfo.versions as List<Map>).each { v ->
            if (v.date && v.date instanceof String) {
                v.date = Date.parse("yyyy-MM-dd'T'mm:HH:ss'Z'", v.date as String)
            }
        }

        // Rewrite YAML file with updated info
        f.text = createYamlDumper().dump(pluginInfo)
        pluginInfo as Map
    }
}

// --rescan-packs: also look for a Forge feature pack in the versions already present (see rescanForgePacks)
def rescanPacks = args?.contains('--rescan-packs')
def fileArgs = (args ?: []).findAll { it != '--rescan-packs' }
def fileArg = fileArgs ? new File(fileArgs[0]) : 'grails-plugins' as File

if (fileArg.file) {
    Utils.processPluginFile(fileArg, rescanPacks)
    println("Processed single file: $fileArg.path")
}
else {
    if (!fileArg.exists() || !fileArg.directory) {
        System.err.println("ERROR: Directory '$fileArg.path' not found in ${('.' as File).absolutePath}")
        System.exit(1)
    }
    List<Map> indexEntries = []
    fileArg.eachFileRecurse(FileType.FILES) { f ->
        def pluginInfo = Utils.processPluginFile(f, rescanPacks)
        // Add to index (include everything from YAML)
        if (pluginInfo) {
            indexEntries << pluginInfo
        }
    }
    // Write index JSON
    def indexFile = 'grails-plugins-index.json' as File
    indexFile.text = JsonOutput.toJson(indexEntries)
    println "Wrote index with ${indexEntries.size()} entries to ${indexFile.path}"
}