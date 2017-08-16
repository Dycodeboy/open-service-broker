package com.swisscom.cloud.sb.broker.license.render

import com.github.jk1.license.License
import com.github.jk1.license.LicenseReportPlugin
import com.github.jk1.license.ManifestData
import com.github.jk1.license.ModuleData
import com.github.jk1.license.PomData
import com.github.jk1.license.ProjectData
import com.github.jk1.license.render.ReportRenderer
import org.gradle.api.Project

class TextReportRenderer implements ReportRenderer{


    private Project project
    private LicenseReportPlugin.LicenseReportExtension config
    private File output
    private int counter
    private String fileName

    public TextReportRenderer() {
    }

    public TextReportRenderer(String filename) {
        this.fileName = filename
    }

    void render(ProjectData data) {
        project = data.project
        config = project.licenseReport
        if (fileName == null) {
            fileName = config.outputDir + "/THIRD-PARTY-NOTICES.txt"
        }
        output = new File(fileName)
        output.text = """
Dependency License Report for $project.name

Dependency License Report for $project.name ${if (!'unspecified'.equals(project.version)) project.version else ''}

"""
        printDependencies(data)
        output << """
This report was generated at ${new Date()}.

"""
    }

    private void printDependencies(ProjectData data) {
        data.allDependencies.sort().each {
            printDependency(it)
        }
    }

    private String printDependency(ModuleData data) {
        boolean projectUrlDone = false
        output << "${++counter}."
        if (data.group) output << " Group: $data.group "
        if (data.name) output << " Name: $data.name "
        if (data.version) output << " Version: $data.version\n\n"

        if (data.poms.isEmpty() && data.manifests.isEmpty()) {
            output << "No license information found\n\n"
            return
        }

        if (!data.manifests.isEmpty() && !data.poms.isEmpty()) {
            ManifestData manifest = data.manifests.first()
            PomData pomData = data.poms.first()
            if (manifest.url && pomData.projectUrl && manifest.url == pomData.projectUrl) {
                output << "Project URL: $manifest.url\n\n"
                projectUrlDone = true
            }
        }

        if (!data.manifests.isEmpty()) {
            ManifestData manifest = data.manifests.first()
            if (manifest.url && !projectUrlDone) {
                output << "Manifest Project URL: $manifest.url\n\n"
            }
            if (manifest.license) {
                if (manifest.license.startsWith("http")) {
                    output << "Manifest license URL: $manifest.license\n\n"
                } else if (manifest.hasPackagedLicense) {
                    output << "Packaged License File: $manifest.license\n\n"
                } else {
                    output << "Manifest License: $manifest.license (Not packaged)\n\n"
                }
            }
        }

        if (!data.poms.isEmpty()) {
            PomData pomData = data.poms.first()
            if (pomData.projectUrl && !projectUrlDone) {
                output << "POM Project URL: $pomData.projectUrl\n\n"
            }
            if (pomData.licenses) {
                pomData.licenses.each { License license ->
                    output << "POM License: $license.name"
                    if (license.url) {
                        if (license.url.startsWith("http")) {
                            output << " - $license.url\n\n"
                        } else if (false) {
                            output << "Packaged License File: $license.url\n\n"
                        } else {
                            output << "License: $license.url\n\n"
                        }
                    }
                }
            }
        }
        if (!data.licenseFiles.isEmpty() && !data.licenseFiles.first().files.isEmpty()) {
            output << 'Embedded license: '
            output << "\n\n"
            output << data.licenseFiles.first().files.collect({ "                    ****************************************                    \n\n" + new File("$config.outputDir/$it").text + "\n"}).join('')
        }
        output << "--------------------------------------------------------------------------------\n\n"
    }
}
