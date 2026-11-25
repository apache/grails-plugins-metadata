#!/usr/bin/env groovy

// Tests of what index-plugins.groovy records of the Forge feature pack of a plugin jar.
// Run with: groovy index-plugins-test.groovy
// The classes of the indexer are compiled from the script beside this one, which is not run: no
// plugin file is read or written and nothing is downloaded but the library the indexer grabs.

import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

def script = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
def shell = new GroovyShell()
shell.parse(new File(script, 'index-plugins.groovy'))
utils = shell.classLoader.loadClass('Utils')
directory = File.createTempDir('index-plugins-test')
failures = []
count = 0

/** What the indexer makes of a jar with this descriptor: the `pack` it records, and what it says on the standard error. */
Map read(String descriptor) {
    def jar = new File(directory, "plugin-${++count}.jar")
    new ZipOutputStream(jar.newOutputStream()).withCloseable { zip ->
        zip.putNextEntry(new ZipEntry('META-INF/grails-forge/feature.yml'))
        zip << descriptor.bytes
        zip.closeEntry()
    }
    def said = new ByteArrayOutputStream()
    def err = System.err
    System.err = new PrintStream(said, true)
    try {
        def pack = new ZipFile(jar).withCloseable { zip -> utils.readForgePack(zip, jar, "https://repo.example/${jar.name}") }
        return [pack: pack, said: said.toString()]
    }
    finally {
        System.err = err
    }
}

String feature(String more) {
    """\
format: 1
kind: feature
name: my-feature
templateApi: 1
compatibility:
  grails: '[9.0.0,10.0.0)'
  forge: '[9.0.0,10.0.0)'
$more
"""
}

String preset(String more) {
    feature(more).replace('kind: feature', 'kind: preset').replace('my-feature', 'my-preset')
}

void test(String name, Closure body) {
    try {
        body()
        println "passed: $name"
    }
    catch(Throwable ex) {
        failures << name
        println "FAILED: $name\n  ${ex.message}"
    }
}

test('a feature for every type of application has no applicationTypes') {
    def result = read(feature(''))
    assert result.pack.kind == 'feature'
    assert result.pack.name == 'my-feature'
    assert !result.pack.containsKey('applicationTypes')
    assert result.pack.sha256 ==~ /[0-9a-f]{64}/
    assert result.said == ''
}

test('each of the five types of application of Grails 9 is recorded without a warning') {
    def result = read(feature('applicationTypes: [web, rest-api, web-plugin, plugin, rest-api-plugin]'))
    assert result.pack.applicationTypes == ['web', 'rest-api', 'web-plugin', 'plugin', 'rest-api-plugin']
    assert result.said == ''
}

test('a pack for web and rest-api-plugin keeps its entry') {
    def result = read(feature('applicationTypes: [web, rest-api-plugin]'))
    assert result.pack != null
    assert result.pack.applicationTypes == ['web', 'rest-api-plugin']
    assert result.said == ''
}

test('a type named twice is recorded once') {
    assert read(feature('applicationTypes: [web, rest-api, web]')).pack.applicationTypes == ['web', 'rest-api']
}

test('a type this indexer does not know is recorded beside the others, with a warning') {
    def result = read(feature('applicationTypes: [web, batch-job]'))
    assert result.pack != null
    assert result.pack.applicationTypes == ['web', 'batch-job']
    assert result.said.startsWith('WARNING: ')
    assert result.said.contains('[batch-job]')
    assert result.said.contains("'applicationTypes'")
    assert result.said.contains('https://repo.example/')
}

test('applicationTypes that cannot be names of types cost the pack its entry, with a warning') {
    ['applicationTypes: []', 'applicationTypes: web', 'applicationTypes: [web, 9]', 'applicationTypes: [web, "Not A Type"]',
     'applicationTypes: [web, [plugin]]', 'applicationTypes:', "applicationTypes: [${(1..21).collect { "type-$it" }.join(', ')}]"].each {
        def result = read(feature(it))
        assert result.pack == null : it
        assert result.said.startsWith('WARNING: Ignoring the Forge feature pack of https://repo.example/') : it
        assert result.said.contains("'applicationTypes'") : it
    }
}

test('the one type of application of a preset is recorded as its applicationTypes') {
    ['web', 'rest-api', 'web-plugin', 'plugin', 'rest-api-plugin'].each {
        def result = read(preset("applicationType: $it"))
        assert result.pack.kind == 'preset'
        assert result.pack.name == 'my-preset'
        assert result.pack.applicationTypes == [it]
        assert !result.pack.containsKey('applicationType')
        assert result.said == ''
    }
}

test('a preset for a type this indexer does not know is recorded with a warning') {
    def result = read(preset('applicationType: batch-job'))
    assert result.pack.applicationTypes == ['batch-job']
    assert result.said.startsWith('WARNING: ')
    assert result.said.contains("'applicationType'")
}

test('a preset that names no type of application, or what is none, gets no entry, with a warning') {
    ['', 'applicationType: [web]', 'applicationType: 9', 'applicationType: "Not A Type"', 'applicationTypes: [web]'].each {
        def result = read(preset(it))
        assert result.pack == null : it
        assert result.said.startsWith('WARNING: Ignoring the Forge feature pack of https://repo.example/') : it
        assert result.said.contains("'applicationType'") : it
    }
}

test('a feature is not given the applicationType only a preset has') {
    def result = read(feature('applicationType: web'))
    assert result.pack != null
    assert !result.pack.containsKey('applicationTypes')
    assert !result.pack.containsKey('applicationType')
}

test('a descriptor that cannot be used still gets no entry, with a warning') {
    def result = read(feature('').replace('kind: feature', 'kind: theme'))
    assert result.pack == null
    assert result.said.startsWith('WARNING: Ignoring the Forge feature pack of')
}

directory.deleteDir()
if (failures) {
    System.err.println("${failures.size()} of the tests failed: $failures")
    System.exit(1)
}
println 'All tests passed'
