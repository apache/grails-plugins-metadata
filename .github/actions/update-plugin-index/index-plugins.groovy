#!/usr/bin/env groovy
@Grab('org.yaml:snakeyaml:2.4')

import java.util.regex.Pattern
import java.util.zip.ZipException
import java.util.zip.ZipFile

import groovy.io.FileType
import groovy.json.JsonOutput
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.xml.XmlSlurper

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

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
                grailsCompatibility = extractGrailsVersionFromPluginJar(url)
                if (grailsCompatibility) {
                    break
                }
            } catch(FileNotFoundException ignore) {
                // Try next
            }
        }

        if (!grailsCompatibility) {
            System.err.println("WARNING: Could not find plugin artifact for $coords:$version")
        }

        def versionInfo = [:] as Map<String, Object>
        if (lastModified) {
            versionInfo.put('date', lastModified as Date)
        }
        if (grailsCompatibility) {
            versionInfo.put('grailsVersion', grailsCompatibility)
        }

        return versionInfo
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
    @CompileDynamic
    static String extractGrailsVersionFromPluginJar(String artifactUrl) throws FileNotFoundException {
        def file = File.createTempFile('plugin-', '.jar').tap {
            it.deleteOnExit()
        }

        println("Downloading plugin artifact from $artifactUrl to extract grailsVersion...")
        doWithConnection(artifactUrl, 'GET') {
            inputStream.withCloseable { is ->
                file.withOutputStream { os ->
                    os << is
                }
            }
        }

        if (!file || !file.exists()) {
            return null
        }

        ZipFile zip = null
        try {
            zip = new ZipFile(file)
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
        catch(ZipException ex) {
            System.err.println("Invalid Zip file at $artifactUrl : ${ex.message}")
            return null
        }
        finally {
            if (zip != null) {
                zip.close()
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

    static Map processPluginFile(File f) {
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
            def availableVersions = fetchPluginVersions(pluginInfo)
            def existingVersions = pluginInfo.get('versions', []) as List<Map>
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
                        grailsVersion: versionInfo.grailsVersion
                ].findAll { k, v -> v != null }

                existingVersions << entry
            }

            pluginInfo.versions = existingVersions.sort { a, b ->
                def verA = Version.build(a.version as String)
                def verB = Version.build(b.version as String)
                return verB <=> verA
            }
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

def specificPluginFile = (args && args.length > 0) ? new File(args[0]) : null
if (specificPluginFile) {
    if (!specificPluginFile.exists() || !specificPluginFile.isFile()) {
        System.err.println("ERROR: Specified file '$specificPluginFile.path' does not exist or is not a file")
        System.exit(1)
    }
    Utils.processPluginFile(specificPluginFile)
    println("Processed single file: $specificPluginFile.path")
}
else {
    def rootDir = 'grails-plugins' as File
    if (!rootDir.exists() || !rootDir.directory) {
        System.err.println("ERROR: Directory 'grails-plugins' not found in ${('.' as File).absolutePath}")
        System.exit(1)
    }
    List<Map> indexEntries = []
    rootDir.eachFileRecurse(FileType.FILES) { f ->
        def pluginInfo = Utils.processPluginFile(f)
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