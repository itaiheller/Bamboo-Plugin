package com.cx.plugin.task;

/**
 * Created by galn on 18/12/2016.
 */

import com.atlassian.bamboo.build.logger.BuildLogger;
import com.atlassian.bamboo.configuration.AdministrationConfiguration;
import com.atlassian.bamboo.configuration.ConfigurationMap;
import com.atlassian.bamboo.task.*;
import com.atlassian.bamboo.v2.build.BuildContext;
import com.atlassian.spring.container.ContainerManager;
import com.cx.client.*;
import com.cx.client.dto.*;
import com.cx.client.exception.CxClientException;
import com.cx.client.rest.dto.CreateOSAScanResponse;
import com.cx.client.rest.dto.OSASummaryResults;
import com.cx.plugin.dto.*;
import com.cx.plugin.utils.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static com.cx.plugin.dto.CxParam.*;

//TODO add the ENV expansion
public class CheckmarxTask implements TaskType {

    public static final Logger log = LoggerFactory.getLogger(CheckmarxTask.class);

    private static final long MAX_ZIP_SIZE_BYTES = 209715200;
    private static final long MAX_OSA_ZIP_SIZE_BYTES = 2146483647;

    private CxClientService cxClientService;
    protected java.net.URL url;
    private File workDirectory;
    private File zipTempFile;
    private String projectStateLink;
    private String osaProjectSummaryLink;
    private CxScanConfiguration config;
    private String scanResultsUrl;

    private BuildLogger buildLogger;
    private CxBuildLoggerAdapter buildLoggerAdapter;
    private HashMap<String, String> configurationMap;
    private BuildContext buildContext;
    private AdministrationConfiguration adminConfig;

    private static final String PDF_REPORT_NAME = "CxSASTReport";
    private static final String OSA_REPORT_NAME = "CxOSAReport";
    private static final String CX_REPORT_LOCATION = File.separator + "Checkmarx" + File.separator + "Reports";
    private static final String TEMP_FILE_NAME_TO_ZIP = "CxZippedSource";

    @NotNull
    public TaskResult execute(@NotNull final TaskContext taskContext) throws TaskException {


        final TaskResultBuilder taskResultBuilder = TaskResultBuilder.newBuilder(taskContext);
        buildLogger = taskContext.getBuildLogger();
        buildLoggerAdapter = new CxBuildLoggerAdapter(buildLogger);
        buildContext = taskContext.getBuildContext();
        Map<String, String> results = new HashMap<String, String>();

        configurationMap = resolveConfigurationMap(taskContext.getConfigurationMap());//resolve the global configuration properties
        workDirectory = taskContext.getWorkingDirectory();
        ScanResults scanResults = null;
        CreateScanResponse createScanResponse = null;
        OSASummaryResults osaSummaryResults = null;
        Exception osaCreateException = null;
        Exception scanWaitException = null;
        boolean fail = false;
        try {
            config = new CxScanConfiguration(configurationMap);
            url = new URL(config.getUrl());
            printConfiguration(config);

            //initialize cx client
            buildLoggerAdapter.info("Initializing Cx client");
            cxClientService = new CxClientServiceImpl(url, config.getUsername(), CxEncryption.decrypt(config.getPassword()));
            cxClientService.setLogger(buildLoggerAdapter);

            cxClientService.checkServerConnectivity();
            //perform login to server
            buildLoggerAdapter.info("Logging into the Checkmarx service.");
            cxClientService.loginToServer();

            //prepare sources (zip it) and send it to scan
            createScanResponse = createScan();

            CreateOSAScanResponse osaScan = null;
            //OSA Scan
            if (config.isOsaEnabled()) {
                try {
                    buildLoggerAdapter.info("Creating OSA scan");
                    buildLoggerAdapter.info("Zipping dependencies");
                    //prepare sources (zip it) for the OSA scan and send it to OSA scan
                    String patternExclusion = "!Checkmarx/Reports/*.*";
                    File zipForOSA = zipWorkspaceFolder(workDirectory.getPath(), "", patternExclusion, MAX_OSA_ZIP_SIZE_BYTES, false);//TODO- lIRAN osa
                    buildLoggerAdapter.info("Sending OSA scan request");
                    osaScan = cxClientService.createOSAScan(createScanResponse.getProjectId(), zipForOSA);
                    osaProjectSummaryLink = CxPluginHelper.composeProjectOSASummaryLink(config.getUrl(), createScanResponse.getProjectId());
                    buildLoggerAdapter.info("OSA scan created successfully");
                    if (zipForOSA.exists() && !zipForOSA.delete()) {
                        buildLoggerAdapter.info("Warning: fail to delete temporary zip file: " + zipForOSA.getAbsolutePath());
                    }
                    buildLoggerAdapter.info("Temporary file deleted");
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    log.error("Fail to create OSA Scan." + e.getMessage(), e);
                    osaCreateException = e;
                }
            }

            //Asynchronous MODE
            if (!config.isSynchronous()) {
                if (osaCreateException != null) {
                    throw osaCreateException;
                }
                buildLoggerAdapter.info("Running in Asynchronous mode. Not waiting for scan to finish");
                return taskResultBuilder.success().build();
            }

            try {
                //wait for SAST scan to finish
                ConsoleScanWaitHandler consoleScanWaitHandler = new ConsoleScanWaitHandler();
                consoleScanWaitHandler.setLogger(buildLoggerAdapter);
                buildLoggerAdapter.info("Waiting for CxSAST scan to finish.");
                cxClientService.waitForScanToFinish(createScanResponse.getRunId(), config.getScanTimeoutInMinutes(), consoleScanWaitHandler);
                buildLoggerAdapter.info("Scan finished. Retrieving scan results");
                //retrieve SAST scan results
                scanResults = cxClientService.retrieveScanResults(createScanResponse.getProjectId());
                scanResultsUrl = CxPluginHelper.composeScanLink(url.toString(), scanResults);
                printResultsToConsole(scanResults);
                addSASTResults(results, scanResults, config);

                if (config.isGeneratePDFReport()) {
                    createPDFReport(scanResults.getScanID());
                }


            } catch (CxClientException e) {
                log.error(e.getMessage(), e);
                scanWaitException = e;
            } catch (InterruptedException e) {
                throw e;

            } catch (Exception e) {
                log.error("Fail to perform CxSAST scan: " + e.getMessage(), e);
                scanWaitException = e;
            }

            if (config.isOsaEnabled()) {

                if (osaCreateException != null) {
                    throw osaCreateException;
                }
                //wait for OSA scan to finish
                OSAConsoleScanWaitHandler osaConsoleScanWaitHandler = new OSAConsoleScanWaitHandler();
                osaConsoleScanWaitHandler.setLogger(buildLoggerAdapter);
                buildLoggerAdapter.info("Waiting for OSA scan to finish");
                cxClientService.waitForOSAScanToFinish(osaScan.getScanId(), -1, osaConsoleScanWaitHandler);
                buildLoggerAdapter.info("OSA scan finished successfully");
                buildLoggerAdapter.info("Creating OSA reports");
                osaSummaryResults = cxClientService.retrieveOSAScanSummaryResults(createScanResponse.getProjectId());
                printOSAResultsToConsole(osaSummaryResults);
                addOSAResults(results, osaSummaryResults, config);

                //OSA PDF and HTML reports
                SimpleDateFormat ft = new SimpleDateFormat("dd_MM_yyyy-HH_mm_ss");
                String now = ft.format(new Date());
                byte[] osaPDF = cxClientService.retrieveOSAScanPDFResults(createScanResponse.getProjectId());
                String pdfFileName = OSA_REPORT_NAME + "_" + now + ".pdf";
                FileUtils.writeByteArrayToFile(new File(workDirectory + CX_REPORT_LOCATION, pdfFileName), osaPDF);
                buildLoggerAdapter.info("OSA PDF report location: " + workDirectory + CX_REPORT_LOCATION + File.separator + pdfFileName);
                String osaHtml = cxClientService.retrieveOSAScanHtmlResults(createScanResponse.getProjectId());
                String htmlFileName = OSA_REPORT_NAME + "_" + now + ".html";
                FileUtils.writeStringToFile(new File(workDirectory + CX_REPORT_LOCATION, htmlFileName), osaHtml, Charset.defaultCharset());
                buildLoggerAdapter.info("OSA HTML report location: " + workDirectory + CX_REPORT_LOCATION + File.separator + htmlFileName);
                buildLoggerAdapter.info("");
            }
            if (scanWaitException != null) {
                throw scanWaitException;
            }

        } catch (MalformedURLException e) {
            buildLogger.addErrorLogEntry("Invalid URL: " + config.getUrl() + ". Exception message: " + e.getMessage());
            log.error("Invalid URL: " + config.getUrl() + ". Exception message: " + e.getMessage(), e);

        } catch (CxClientException e) {
            buildLogger.addErrorLogEntry("Caught exception: " + e.getMessage());
            log.error("Caught exception: " + e.getMessage(), e);
            fail = true;

        } catch (NumberFormatException e) {
            buildLogger.addErrorLogEntry("Invalid preset id. " + e.getMessage());
            log.error("Invalid preset id: " + e.getMessage(), e);
            fail = true;

        } catch (InterruptedException e) {
            buildLogger.addErrorLogEntry("Interrupted exception: " + e.getMessage());
            log.error("Interrupted exception: " + e.getMessage(), e);

            if (cxClientService != null && createScanResponse != null) {
                log.error("Canceling scan on the Checkmarx server...");
                cxClientService.cancelScan(createScanResponse.getRunId());
            }
            throw new TaskException(e.getMessage());

        } catch (Exception e) {
            buildLogger.addErrorLogEntry("Unexpected exception: " + e.getMessage());
            log.error("Unexpected exception: " + e.getMessage(), e);
            throw new TaskException(e.getMessage());
        } finally {

            deleteTempFiles();
        }

        buildContext.getBuildResult().getCustomBuildData().putAll(results);

        //assert if expected exception is thrown  OR when vulnerabilities under threshold
        if (assertVulnerabilities(scanResults, osaSummaryResults) || fail) {
            return taskResultBuilder.failed().build();
        }

        return taskResultBuilder.success().build();
    }

    private void deleteTempFiles() {

        try {
            String tempDir = System.getProperty("java.io.tmpdir");
            CxFileChecker.deleteFile(tempDir, TEMP_FILE_NAME_TO_ZIP);
        } catch (Exception e) {
            buildLoggerAdapter.error("Failed to delete temp files: " +e.getMessage());
        }

    }

    private HashMap<String, String> resolveConfigurationMap(ConfigurationMap configMap) throws TaskException {
        adminConfig = (AdministrationConfiguration) ContainerManager.getComponent(ADMINISTRATION_CONFIGURATION);
        configurationMap = new HashMap<String, String>();

        if (COSTUME_CONFIGURATION_SERVER.equals(configMap.get(SERVER_CREDENTIALS_SECTION))) {
            configurationMap.put(SERVER_URL, configMap.get(SERVER_URL));
            configurationMap.put(USER_NAME, configMap.get(USER_NAME));
            configurationMap.put(PASSWORD, configMap.get(PASSWORD));
        } else {
            configurationMap.put(SERVER_URL, getAdminConfig(GLOBAL_SERVER_URL));
            configurationMap.put(USER_NAME, getAdminConfig(GLOBAL_USER_NAME));
            configurationMap.put(PASSWORD, getAdminConfig(GLOBAL_PASSWORD));
        }

        configurationMap.put(PROJECT_NAME, configMap.get(PROJECT_NAME));

        String presetId = configMap.get(PRESET_ID);
        if (!StringUtils.isNumeric(presetId)) {
            throw new TaskException("Invalid preset Id");
        }

        String teamName = configMap.get(TEAM_PATH_NAME);
        if (StringUtils.isEmpty(teamName)) {
            throw new TaskException("Invalid team path");
        }

        configurationMap.put(PRESET_ID, presetId);
        configurationMap.put(PRESET_NAME, StringUtils.defaultString(configMap.get(PRESET_NAME)));
        configurationMap.put(TEAM_PATH_ID, StringUtils.defaultString(configMap.get(TEAM_PATH_ID)));
        configurationMap.put(TEAM_PATH_NAME, teamName);

        if (COSTUME_CONFIGURATION_CXSAST.equals(configMap.get(CXSAST_SECTION))) {
            configurationMap.put(FOLDER_EXCLUSION, configMap.get(FOLDER_EXCLUSION));
            configurationMap.put(FILTER_PATTERN, configMap.get(FILTER_PATTERN));
            configurationMap.put(SCAN_TIMEOUT_IN_MINUTES, configMap.get(SCAN_TIMEOUT_IN_MINUTES));
        } else {
            configurationMap.put(FOLDER_EXCLUSION, getAdminConfig(GLOBAL_FOLDER_EXCLUSION));
            configurationMap.put(FILTER_PATTERN, getAdminConfig(GLOBAL_FILTER_PATTERN));
            configurationMap.put(SCAN_TIMEOUT_IN_MINUTES, getAdminConfig(GLOBAL_SCAN_TIMEOUT_IN_MINUTES));
        }
        configurationMap.put(IS_INCREMENTAL, configMap.get(IS_INCREMENTAL));
        configurationMap.put(GENERATE_PDF_REPORT, configMap.get(GENERATE_PDF_REPORT));
        configurationMap.put(OSA_ENABLED, configMap.get(OSA_ENABLED));

        if (COSTUME_CONFIGURATION_CONTROL.equals(configMap.get(SCAN_CONTROL_SECTION))) {
            configurationMap.put(IS_SYNCHRONOUS, configMap.get(IS_SYNCHRONOUS));
            configurationMap.put(THRESHOLDS_ENABLED, configMap.get(THRESHOLDS_ENABLED));
            configurationMap.put(HIGH_THRESHOLD, configMap.get(HIGH_THRESHOLD));
            configurationMap.put(MEDIUM_THRESHOLD, configMap.get(MEDIUM_THRESHOLD));
            configurationMap.put(LOW_THRESHOLD, configMap.get(LOW_THRESHOLD));
            configurationMap.put(OSA_THRESHOLDS_ENABLED, configMap.get(OSA_THRESHOLDS_ENABLED));
            configurationMap.put(OSA_HIGH_THRESHOLD, configMap.get(OSA_HIGH_THRESHOLD));
            configurationMap.put(OSA_MEDIUM_THRESHOLD, configMap.get(OSA_MEDIUM_THRESHOLD));
            configurationMap.put(OSA_LOW_THRESHOLD, configMap.get(OSA_LOW_THRESHOLD));
        } else {
            configurationMap.put(IS_SYNCHRONOUS, getAdminConfig(GLOBAL_IS_SYNCHRONOUS));
            configurationMap.put(THRESHOLDS_ENABLED, getAdminConfig(GLOBAL_THRESHOLDS_ENABLED));
            configurationMap.put(HIGH_THRESHOLD, getAdminConfig(GLOBAL_HIGH_THRESHOLD));
            configurationMap.put(MEDIUM_THRESHOLD, getAdminConfig(GLOBAL_MEDIUM_THRESHOLD));
            configurationMap.put(LOW_THRESHOLD, getAdminConfig(GLOBAL_LOW_THRESHOLD));
            configurationMap.put(OSA_THRESHOLDS_ENABLED, getAdminConfig(GLOBAL_OSA_THRESHOLDS_ENABLED));
            configurationMap.put(OSA_HIGH_THRESHOLD, getAdminConfig(GLOBAL_OSA_HIGH_THRESHOLD));
            configurationMap.put(OSA_MEDIUM_THRESHOLD, getAdminConfig(GLOBAL_OSA_MEDIUM_THRESHOLD));
            configurationMap.put(OSA_LOW_THRESHOLD, getAdminConfig(GLOBAL_OSA_LOW_THRESHOLD));
        }

        return configurationMap;
    }

    private void addSASTResults(Map<String, String> results, ScanResults scanResults, CxScanConfiguration config) {
        results.put(CxResultsConst.HIGH_RESULTS, String.valueOf(scanResults.getHighSeverityResults()));
        results.put(CxResultsConst.MEDIUM_RESULTS, String.valueOf(scanResults.getMediumSeverityResults()));
        results.put(CxResultsConst.LOW_RESULTS, String.valueOf(scanResults.getLowSeverityResults()));
        results.put(CxResultsConst.SAST_SUMMARY_RESULTS_LINK, StringUtils.defaultString(projectStateLink));

        results.put(CxResultsConst.THRESHOLD_ENABLED, String.valueOf(config.isSASTThresholdEnabled()));

        if (config.isThresholdsEnabled()) {
            String highThreshold = (config.getHighThreshold() == null ? "null" : String.valueOf(config.getHighThreshold()));
            String mediumThreshold = (config.getMediumThreshold() == null ? "null" : String.valueOf(config.getMediumThreshold()));
            String lowThreshold = (config.getLowThreshold() == null ? "null" : String.valueOf(config.getLowThreshold()));

            results.put(CxResultsConst.HIGH_THRESHOLD, highThreshold);
            results.put(CxResultsConst.MEDIUM_THRESHOLD, mediumThreshold);
            results.put(CxResultsConst.LOW_THRESHOLD, lowThreshold);
        }

        results.put(CxResultsConst.SAST_RESULTS_READY, OPTION_TRUE);
    }

    private void addOSAResults(Map<String, String> results, OSASummaryResults osaSummaryResults, CxScanConfiguration config) {

        results.put(CxResultsConst.OSA_HIGH_RESULTS, String.valueOf(osaSummaryResults.getHighVulnerabilities()));
        results.put(CxResultsConst.OSA_MEDIUM_RESULTS, String.valueOf(osaSummaryResults.getMediumVulnerabilities()));
        results.put(CxResultsConst.OSA_LOW_RESULTS, String.valueOf(osaSummaryResults.getLowVulnerabilities()));

        results.put(CxResultsConst.OSA_SUMMARY_RESULTS_LINK, StringUtils.defaultString(osaProjectSummaryLink));

        results.put(CxResultsConst.OSA_VULNERABLE_LIBRARIES, String.valueOf(osaSummaryResults.getHighVulnerabilityLibraries() + osaSummaryResults.getMediumVulnerabilityLibraries() + osaSummaryResults.getLowVulnerabilityLibraries()));
        results.put(CxResultsConst.OSA_OK_LIBRARIES, String.valueOf(osaSummaryResults.getNonVulnerableLibraries()));

        results.put(CxResultsConst.OSA_THRESHOLD_ENABLED, String.valueOf(config.isOSAThresholdEnabled()));

        if (config.isOSAThresholdEnabled()) {

            String osaHighThreshold = (config.getOsaHighThreshold() == null ? "null" : String.valueOf(config.getOsaHighThreshold()));
            String osaMediumThreshold = (config.getOsaMediumThreshold() == null ? "null" : String.valueOf(config.getOsaMediumThreshold()));
            String osaLowThreshold = (config.getOsaLowThreshold() == null ? "null" : String.valueOf(config.getOsaLowThreshold()));

            results.put(CxResultsConst.OSA_HIGH_THRESHOLD, osaHighThreshold);
            results.put(CxResultsConst.OSA_MEDIUM_THRESHOLD, osaMediumThreshold);
            results.put(CxResultsConst.OSA_LOW_THRESHOLD, osaLowThreshold);
        }

        results.put(CxResultsConst.OSA_RESULTS_READY, OPTION_TRUE);
    }

    private CreateScanResponse createScan() throws CxClientException, TaskException, IOException, InterruptedException {

        //prepare sources to scan (zip them)
        buildLoggerAdapter.info("Zipping sources");
        zipTempFile = zipWorkspaceFolder(workDirectory.getPath(), config.getFolderExclusions(), config.getFilterPattern(), MAX_ZIP_SIZE_BYTES, true);

        //send sources to scan
        byte[] zippedSources = getBytesFromZippedSources();
        LocalScanConfiguration conf = generateScanConfiguration(zippedSources);
        CreateScanResponse createScanResponse = cxClientService.createLocalScan(conf);
        projectStateLink = CxPluginHelper.composeProjectStateLink(url.toString(), createScanResponse.getProjectId());
        buildLoggerAdapter.info("Scan created successfully. Link to project state: " + projectStateLink);

        if (zipTempFile.exists() && !zipTempFile.delete()) {
            buildLoggerAdapter.info("Warning: fail to delete temporary zip file: " + zipTempFile.getAbsolutePath());
        } else {
            buildLoggerAdapter.info("Temporary file deleted");
        }
        return createScanResponse;
    }

    private String getWorkspace() throws CxAbortException {
        final Map<Long, String> checkoutLocations = this.buildContext.getCheckoutLocation();
        if (checkoutLocations.isEmpty()) {
            throw new CxAbortException("No source code repository found");
        }
        if (checkoutLocations.size() > 1) {
            buildLoggerAdapter.warn("Warning: more than one workspace found. Using the first one.");
        }
        return checkoutLocations.entrySet().iterator().next().getValue();
    }

    private File zipWorkspaceFolder(String baseZipDir, String folderExclusions, String filterPattern, long maxZipSizeInBytes, boolean writeToLog) throws IOException, InterruptedException {
        final String combinedFilterPattern = CxFolderPattern.generatePattern(folderExclusions, filterPattern, buildLoggerAdapter);
        CxZip cxZip = new CxZip(TEMP_FILE_NAME_TO_ZIP).setMaxZipSizeInBytes(maxZipSizeInBytes);
        return cxZip.zipWorkspaceFolder(baseZipDir, combinedFilterPattern, buildLoggerAdapter, writeToLog);
    }

    private LocalScanConfiguration generateScanConfiguration(byte[] zippedSources) {
        LocalScanConfiguration ret = new LocalScanConfiguration();
        ret.setProjectName(config.getProjectName());
        ret.setClientOrigin(ClientOrigin.BAMBOO);
        ret.setFolderExclusions(config.getFolderExclusions());
        ret.setFullTeamPath(config.getFullTeamPath());
        ret.setIncrementalScan(config.isIncremental());
        ret.setPresetId(config.getPresetId());
        ret.setZippedSources(zippedSources);
        ret.setFileName(config.getProjectName());

        return ret;
    }

    private byte[] getBytesFromZippedSources() throws TaskException {
        buildLoggerAdapter.info("Converting zipped sources to byte array");
        byte[] zipFileByte;
        InputStream fileStream = null;
        try {
            fileStream = new FileInputStream(zipTempFile);
            zipFileByte = IOUtils.toByteArray(fileStream);
        } catch (Exception e) {
            throw new TaskException("Fail to set zipped file into project: " + e.getMessage(), e);
        } finally {
            IOUtils.closeQuietly(fileStream);
        }
        return zipFileByte;
    }

    private void printConfiguration(CxScanConfiguration config) {
        buildLoggerAdapter.info("----------------------------Configurations:-----------------------------");
        buildLoggerAdapter.info("Username: " + config.getUsername());
        buildLoggerAdapter.info("URL: " + config.getUrl());
        buildLoggerAdapter.info("Project name: " + config.getProjectName());
        buildLoggerAdapter.info("Scan timeout in minutes: " + (config.getScanTimeoutInMinutes() <= 0 ? "" : config.getScanTimeoutInMinutes()));
        buildLoggerAdapter.info("Full team path: " + config.getFullTeamPath());
        buildLoggerAdapter.info("Preset: " + config.getPresetName());
        buildLoggerAdapter.info("Is incremental scan: " + config.isIncremental());
        buildLoggerAdapter.info("Folder exclusions: " + (config.getFolderExclusions()));
        buildLoggerAdapter.info("Is synchronous scan: " + config.isSynchronous());
        buildLoggerAdapter.info("Generate PDF report: " + config.isGeneratePDFReport());
        buildLoggerAdapter.info("CxSAST thresholds enabled: " + config.isThresholdsEnabled());
        if (config.isThresholdsEnabled()) {
            buildLoggerAdapter.info("CxSAST high threshold: " + (config.getHighThreshold() == null ? "[No Threshold]" : config.getHighThreshold()));
            buildLoggerAdapter.info("CxSAST medium threshold: " + (config.getMediumThreshold() == null ? "[No Threshold]" : config.getMediumThreshold()));
            buildLoggerAdapter.info("CxSAST low threshold: " + (config.getLowThreshold() == null ? "[No Threshold]" : config.getLowThreshold()));
        }
        buildLoggerAdapter.info("CxOSA enabled: " + config.isOsaEnabled());
        if (config.isOsaEnabled()) {
            buildLoggerAdapter.info("CxOSA thresholds enabled: " + config.isOsaThresholdsEnabled());
            if (config.isOsaThresholdsEnabled()) {
                buildLoggerAdapter.info("CxOSA high threshold: " + (config.getOsaHighThreshold() == null ? "[No Threshold]" : config.getOsaHighThreshold()));
                buildLoggerAdapter.info("CxOSA medium threshold: " + (config.getOsaMediumThreshold() == null ? "[No Threshold]" : config.getOsaMediumThreshold()));
                buildLoggerAdapter.info("CxOSA low threshold: " + (config.getOsaLowThreshold() == null ? "[No Threshold]" : config.getOsaLowThreshold()));
            }
        }
        buildLoggerAdapter.info("------------------------------------------------------------------------");
    }

    private void printResultsToConsole(ScanResults scanResults) {
        buildLoggerAdapter.info("----------------------------Checkmarx Scan Results(CxSAST):-------------------------------");
        buildLoggerAdapter.info("High severity results: " + scanResults.getHighSeverityResults());
        buildLoggerAdapter.info("Medium severity results: " + scanResults.getMediumSeverityResults());
        buildLoggerAdapter.info("Low severity results: " + scanResults.getLowSeverityResults());
        buildLoggerAdapter.info("Info severity results: " + scanResults.getInfoSeverityResults());
        buildLoggerAdapter.info("");
        buildLoggerAdapter.info("Scan results location: " + scanResultsUrl);
        buildLoggerAdapter.info("------------------------------------------------------------------------------------------\n");
    }

    private void printOSAResultsToConsole(OSASummaryResults osaSummaryResults) {
        buildLoggerAdapter.info("----------------------------Checkmarx Scan Results(CxOSA):-------------------------------");
        buildLoggerAdapter.info("");
        buildLoggerAdapter.info("------------------------");
        buildLoggerAdapter.info("Vulnerabilities Summary:");
        buildLoggerAdapter.info("------------------------");
        buildLoggerAdapter.info("OSA high severity results: " + osaSummaryResults.getHighVulnerabilities());
        buildLoggerAdapter.info("OSA medium severity results: " + osaSummaryResults.getMediumVulnerabilities());
        buildLoggerAdapter.info("OSA low severity results: " + osaSummaryResults.getLowVulnerabilities());
        buildLoggerAdapter.info("Vulnerability score: " + osaSummaryResults.getVulnerabilityScore());
        buildLoggerAdapter.info("");
        buildLoggerAdapter.info("-----------------------");
        buildLoggerAdapter.info("Libraries Scan Results:");
        buildLoggerAdapter.info("-----------------------");
        buildLoggerAdapter.info("Open-source libraries: " + osaSummaryResults.getTotalLibraries());
        buildLoggerAdapter.info("Vulnerable and outdated: " + osaSummaryResults.getVulnerableAndOutdated());
        buildLoggerAdapter.info("Vulnerable and updated: " + osaSummaryResults.getVulnerableAndUpdated());
        buildLoggerAdapter.info("Non-vulnerable libraries: " + osaSummaryResults.getNonVulnerableLibraries());
        buildLoggerAdapter.info("");
        buildLoggerAdapter.info("OSA scan results location: " + osaProjectSummaryLink);
        buildLoggerAdapter.info("-----------------------------------------------------------------------------------------");
    }

    private boolean assertVulnerabilities(ScanResults scanResults, OSASummaryResults osaSummaryResults) throws TaskException {

        StringBuilder res = new StringBuilder("");
        boolean fail = false;
        if (config.isSASTThresholdEnabled() && scanResults != null) {
            fail = isFail(scanResults.getHighSeverityResults(), config.getHighThreshold(), res, "high", "CxSAST ");
            fail |= isFail(scanResults.getMediumSeverityResults(), config.getMediumThreshold(), res, "medium", "CxSAST ");
            fail |= isFail(scanResults.getLowSeverityResults(), config.getLowThreshold(), res, "low", "CxSAST ");
        }
        if (config.isOSAThresholdEnabled() && osaSummaryResults != null) {
            fail |= isFail(osaSummaryResults.getHighVulnerabilities(), config.getOsaHighThreshold(), res, "high", "CxOSA ");
            fail |= isFail(osaSummaryResults.getMediumVulnerabilities(), config.getOsaMediumThreshold(), res, "medium", "CxOSA ");
            fail |= isFail(osaSummaryResults.getLowVulnerabilities(), config.getOsaLowThreshold(), res, "low", "CxOSA ");
        }

        if (fail) {
            buildLoggerAdapter.error("*************************");
            buildLoggerAdapter.error("The Build Failed due to: ");
            buildLoggerAdapter.error("*************************");
            String[] lines = res.toString().split("\\n");
            for (String s : lines) {
                buildLoggerAdapter.error(s);
                log.info(s);
            }
            buildLoggerAdapter.error("-----------------------------------------------------------------------------------------\n");
            buildLoggerAdapter.error("");
        }
        return fail;
    }

    private boolean isFail(int result, Integer threshold, StringBuilder res, String severity, String severityType) {
        boolean fail = false;
        if (threshold != null && result > threshold) {
            res.append(severityType).append(severity).append(" severity results are above threshold. Results: ").append(result).append(". Threshold: ").append(threshold).append("\n");
            fail = true;
        }
        return fail;
    }

    private String getAdminConfig(String key) {
        return StringUtils.defaultString(adminConfig.getSystemProperty(key));
    }

    private void createPDFReport(long scanId) throws InterruptedException {
        buildLoggerAdapter.info("Generating PDF report");
        byte[] scanReport;
        try {
            scanReport = cxClientService.getScanReport(scanId, ReportType.PDF);
            SimpleDateFormat df = new SimpleDateFormat("dd_MM_yyyy-HH_mm_ss");
            String now = df.format(new Date());
            String pdfFileName = PDF_REPORT_NAME + "_" + now + ".pdf";
            FileUtils.writeByteArrayToFile(new File(getWorkspace() + CX_REPORT_LOCATION, pdfFileName), scanReport);
            buildLoggerAdapter.info("PDF report location: " + getWorkspace() + CX_REPORT_LOCATION + File.separator + pdfFileName);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            buildLogger.addErrorLogEntry("Fail to generate PDF report");
            log.error("Fail to generate PDF report ", e);        }
    }


}