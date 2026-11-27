# Grails Plugin Page Data Source

This repository hosts the data used by the [Grails Plugin Page](https://grails.apache.org/plugins.html) to list and display plugin information.

## Plugin Data Structure

Plugins are organized in the `grails-plugins/` directory using a hierarchical structure based on Maven coordinates. Each plugin is represented by a YAML file containing its metadata and version information.

### Directory Organization

Plugins are organized by their Maven groupId. The directory structure mirrors the groupId with the YAML file named as the artifactId:
```
grails-plugins (root directory, not part of the coordinates)
├───cloud
│   └───wondrify
│       └───asset-pipeline-grails.yml
└───org
    ├───apache
    │   └───grails
    │       ├───grails-async.yml
    │       └───grails-cache.yml
    └───grails
        └───plugins
            ├───grails-mail.yml
            └───grails-logical-delete.yml
```

## Adding Your Plugin to the Grails Plugin Page

To have your plugin listed on the Grails Plugin Page, follow these steps:

1. **Fork this repository**

2. **Create the directory structure** – If needed, create the appropriate directory structure based on your Maven groupId:
   - For `com.example.plugins` → `grails-plugins/com/example/plugins/`
   - For `org.myorg` → `grails-plugins/org/myorg/`
   - The directory structure must match your groupId exactly

3. **Create a YAML file** for your plugin named `<artifactId>.yml` with the following structure:
```yaml
name: Your Plugin Name # Try to not use the word "Grails" here, it is implied and not helpful
desc: A concise description of your plugin # Write a meaningful description, do not just repeat the name
coords: com.example:my-plugin # Maven coordinates in format groupId:artifactId
owner: Your name or organization # for example, your GitHub username
vcs: https://github.com/your-github-username/your-plugin-repo-name # URL to your plugin's GitHub repository
docs: https://github.com/your-github-username/your-plugin-repo-name#readme # URL to your plugin's documentation
maven-repo: https://repo1.maven.org/maven2 # Or the repo where your plugin is published
labels: # List of relevant labels (see the plugin page for label conventions)
  - relevant-label
  - check-existing-labels
  - do-not-put-grails-here
licenses: # List of license identifiers (e.g., Apache-2.0, MIT, GPL-3.0)
  - Apache-2.0
```

4. **Place the file** in the appropriate directory:
   - File name format: `<artifactId>.yml` (use the artifact ID from your coords, not the plugin name)
   - Example paths:
     - `grails-plugins/com/example/plugins/my-artifact-id.yml` (if coords is `com.example.plugins:my-artifact-id`)
     - `grails-plugins/org/myorg/my-plugin.yml` (if coords is `org.myorg:my-plugin`)

5. **Create a Pull Request** with your changes.

6. **Wait for approval** - A member of the Grails team will review and merge your PR. Once merged, your plugin will appear on the Grails Plugin Page.

## Versions

A "versions" property will be automatically generated and kept up to date in your YAML-file. This property is a list detailing plugin releases, including their version, release date, and compatible Grails version. Crucially, the "grailsVersion" within each release entry is sourced directly from your plugin's Grails Plugin Descriptor. This data is vital for users to figure out compatibility with their application's Grails version, **so please verify its accuracy before publishing**.

```yaml
versions:
  - version: 4.0.1
    date: 2026-02-11T12:58:35Z
    grailsVersion: 7.0.0 > *
  - version: 4.0.0
    date: 2025-10-16T16:08:47Z
    grailsVersion: 7.0.0 > *
```

### Forge feature packs

If the main jar of a plugin release contains a Forge feature pack (`META-INF/grails-forge/feature.yml`, which the `grails-plugin` Gradle plugin packages from `src/main/forge`), the indexer adds a `pack` property to that version. It lets the Grails CLI select the feature of your plugin by its name (`grails create-app --features <name>`) without downloading every release to find it.

```yaml
versions:
  - version: 4.1.0
    date: 2026-11-20T10:00:00Z
    grailsVersion: 9.0.0 > *
    pack:
      format: 1
      kind: feature
      name: my-feature
      templateApi: 1
      applicationTypes:   # left out for a feature pack that is for every type
        - web
      compatibility:
        grails: '[9.0.0,10.0.0)'
        forge: '[9.0.0,10.0.0)'
      sha256: 5f2c...     # of the jar; the CLI refuses a jar with another one
```

The `pack` property is copied from the descriptor inside the jar, which is read as data only; nothing of the jar is executed. Only the properties shown are copied, and the `sha256` is that of the jar as it was downloaded. **Do not write or edit `pack` by hand**: the `sha256` is what ties the entry to one jar. A jar whose descriptor cannot be read gets no `pack` (the indexer logs a warning and goes on).

`applicationTypes` holds the types of application the pack is for: `web`, `rest-api`, `web-plugin`, `plugin` and `rest-api-plugin`. A feature pack that declares none is for all of them and has no `applicationTypes`. A preset (`kind: preset`) is for one type, the `applicationType` of its descriptor, which is recorded as the one entry of `applicationTypes`. A type the indexer does not know is recorded as the descriptor names it, beside the others, and the indexer logs a warning: the pack keeps its entry for the types it is validly for, and the Grails CLI passes over a type it does not know.

Versions that were indexed before they had a pack recorded are only looked at again when the indexer is run with `--rescan-packs`, for example `groovy index-plugins.groovy --rescan-packs grails-plugins/com/example/my-plugin.yml`.

What the indexer records of a pack is tested by `groovy index-plugins-test.groovy`, which reads no plugin file and downloads no plugin.

## Updating Your Plugin

Once your plugin's YAML file is accepted and merged into this repository, the system will periodically scan for new plugin versions and update the `versions` list accordingly. You do not need to manually update the YAML file when you release a new version of your plugin.

However, if the automatically scanned version information is somehow incorrect, you can manually edit the YAML file to correct it. This is useful if the `grailsVersion` or other metadata from your plugin descriptor needs to be adjusted. Any version already present in the YAML file will not be updated automatically.

If the plugin has been published to multiple maven coordinates or repositories over time, these versions can still be aggregated in the YAML-file by adding override properties `coords` and/or `maven-repo` to the version items.

```yaml
# Example from org.grails.plugins:grails-mail
version:
  - version: 5.0.0
    date: 2025-06-10T18:09:54Z
    grailsVersion: 7.0.0 > *
  - version: 4.0.2
    date: 2025-06-18T05:56:58Z
    grailsVersion: 6.0.0 > *
    coords: org.grails.plugins:mail
  - version: 4.0.1
    date: 2025-04-11T06:13:16Z
    grailsVersion: 6.0.0 > *
    coords: org.grails.plugins:mail
  - version: 4.0.0
    date: 2024-04-30T07:30:11Z
    grailsVersion: 6.0.0 > *
    coords: org.grails.plugins:mail
  - version: 3.0.0
    date: 2021-02-24T05:35:23Z
    grailsVersion: 3.0 > *
    coords: org.grails.plugins:mail
    maven-repo: https://repo.grails.org/plugins
```


## Deprecating Plugins

If the development of a plugin is discontinued or if it for other reasons is not relevant anymore, you can add a `deprecated` property to the YAML file, with a description of why it is deprecated. This information will be displayed on the [Grails Plugin Page](https://grails.apache.org/plugins.html) entry.

## Removing Plugins

If the plugin should no longer be displayed in the plugin index, you can remove the YAML file, and it will be removed from the index on the next indexing operation.
