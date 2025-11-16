# Grails Plugin Page Data Source
This repository hosts the data used by the [Grails Plugin Page](https://grails.apache.org/plugins.html) to list and display plugin information.

## Adding Your Plugin to the Grails Plugin Page
To have your plugin listed on the Grails Plugin Page, follow these steps:
 
1. Open the [grails-plugins.json](grails-plugins.json) file in this repository.
2. Add a new entry with the following structure to the **end of the JSON list**:

```json
{
    "bintrayPackage": {
        "name": "Your Plugin Name",
        "repo": "your-plugin-repo-name",
        "owner": "your-github-username",
        "desc": "A concise description of your plugin",
        "labels": [
            "relevant-label",
            "check-existing-labels"
        ],
        "licenses": [
            "Apache-2.0"
        ],
        "issueTrackerUrl": "https://github.com/your-github-username/your-plugin-repo/issues",
        "latestVersion": "1.0",
        "updated": "2024-12-25T04:00:40.855Z",
        "systemIds": [
            "your.plugin.maven:coords"
        ],
        "vcsUrl": "https://github.com/your-github-username/your-plugin-repo-name"
    },
    "documentationUrl": "https://github.com/your-github-username/your-plugin-repo-name#readme",
    "mavenMetadataUrl": "https://repo1.maven.org/maven2/your/plugin/maven/coords/maven-metadata.xml"
}
```
3. Create a **Pull Request** with your changes.
4. Wait for approval. Once merged, your plugin will appear on the Grails Plugin Page.

## Updating Your Plugin
When you release a new version of your plugin, the system will automatically generate a Pull Request to update your plugin's entry in this repository. A member of the Grails team will review and merge the update.