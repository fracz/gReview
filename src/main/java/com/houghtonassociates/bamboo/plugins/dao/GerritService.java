/**
 * Copyright 2012 Houghton Associates
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.houghtonassociates.bamboo.plugins.dao;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.ListIterator;

import org.apache.log4j.Logger;

import com.atlassian.bamboo.repository.RepositoryException;
import com.houghtonassociates.bamboo.plugins.dao.GerritChangeVO.Approval;
import com.houghtonassociates.bamboo.plugins.dao.GerritChangeVO.FileSet;
import com.houghtonassociates.bamboo.plugins.dao.GerritChangeVO.PatchSet;
import com.sonyericsson.hudson.plugins.gerrit.gerritevents.GerritConnectionConfig;
import com.sonyericsson.hudson.plugins.gerrit.gerritevents.GerritQueryException;
import com.sonyericsson.hudson.plugins.gerrit.gerritevents.GerritQueryHandler;
import com.sonyericsson.hudson.plugins.gerrit.gerritevents.ssh.Authentication;
import com.sonyericsson.hudson.plugins.gerrit.gerritevents.ssh.SshConnection;
import com.sonyericsson.hudson.plugins.gerrit.gerritevents.ssh.SshConnectionFactory;
import com.sonyericsson.hudson.plugins.gerrit.gerritevents.ssh.SshException;
import com.sonyericsson.hudson.plugins.gerrit.gerritevents.workers.cmd.AbstractSendCommandJob;
import net.sf.json.JSONObject;

/**
 * Facade for witing with ssh, gerrit-events, parsing JSON results, and Gerrit related data.
 */
@SuppressWarnings("nls")
public class GerritService {

    /**
     * Default query that is sent to the Gerrit when querying for changes to build. Can be overridden by user's configuration.
     */
    public static final String DEFAULT_QUERY = "is:open (-is:reviewed OR (-label:Verified=-1 -label:Verified=1))";
    
    public static final String DEFAULT_VERIFIED_FLAG = "+1";

    public static final String DEFAULT_UNVERIFIED_FLAG = "-1";

    private static final Logger log = Logger.getLogger(GerritService.class);

    private final String verifiedFlag;

    private final String unverifiedFlag;

    private GerritQueryHandler gQueryHandler = null;
    private GerritCmdProcessor cmdProcessor = null;
    private String strHost;
    private int port = 29418;
    private Authentication auth = null;

    // public GerritService(String strHost, int port, File sshKeyFile,
    // String strUsername, String phrase) {
    // auth = new Authentication(sshKeyFile, strUsername, phrase);
    // this.strHost = strHost;
    // this.port = port;
    // }

    public GerritService(String strHost, int port, Authentication auth, String verifiedFlag, String unverifiedFlag) {
        this.strHost = strHost;
        this.port = port;
        this.auth = auth;
        this.verifiedFlag = verifiedFlag;
        this.unverifiedFlag = unverifiedFlag;
    }

    public void testGerritConnection() throws RepositoryException {
        SshConnection sshConnection = null;

        try {
            sshConnection =
                SshConnectionFactory.getConnection(strHost, port, auth);
        } catch (IOException e) {
            throw new RepositoryException(
                "Failed to establish connection to Gerrit!");
        }

        if (!sshConnection.isConnected()) {
            throw new RepositoryException(
                "Failed to establish connection to Gerrit!");
        } else {
            sshConnection.disconnect();
        }
    } 

    private class GerritCmdProcessor extends AbstractSendCommandJob {

        protected GerritCmdProcessor(GerritConnectionConfig config) {
            super(config);
        }

        @Override
        public void run() {

        }
    }
    
    public boolean
                    addComment(int changeNumber, int patchNumber, String message) {
        return getGerritCmdProcessor().sendCommand(
            String.format("gerrit review --message '%s' %s,%s", message,
                changeNumber, patchNumber));
    }

    /**
     * Called when build ends. Mark the change in Gerrit verified or not, depending on the build result.
     * 
     * @param pass
     *            {@code true} if build was successfull, {@code false} otherwise
     * @param changeNumber
     *            number of the change that was built
     * @param patchNumber
     *            number of the patchset from a change that triggered the build
     * @param message
     *            comment to add
     * @return {@code true} if the command was sent successfully, {@code false} otherwise
     */
    public boolean verifyChange(Boolean pass, Integer changeNumber, Integer patchNumber, String message) {
        String command = String.format("gerrit review --message '%s' --verified %s %s,%s", message, pass ? verifiedFlag : unverifiedFlag,
                changeNumber.intValue(), patchNumber.intValue());
        log.debug("Sending Command: " + command);
        return getGerritCmdProcessor().sendCommand(command);
    }

    private GerritCmdProcessor getGerritCmdProcessor() {
        if (cmdProcessor == null) {
            cmdProcessor = new GerritCmdProcessor(new GerritConnectionConfig() {

                @Override
                public File getGerritAuthKeyFile() {
                    return auth.getPrivateKeyFile();
                }

                @Override
                public String getGerritAuthKeyFilePassword() {
                    return auth.getPrivateKeyFilePassword();
                }

                @Override
                public Authentication getGerritAuthentication() {
                    return auth;
                }

                @Override
                public String getGerritHostName() {
                    return strHost;
                }

                @Override
                public int getGerritSshPort() {
                    return port;
                }

                @Override
                public String getGerritUserName() {
                    return auth.getUsername();
                }

                @Override
                public int getNumberOfReceivingWorkerThreads() {
                    return 2;
                }

                @Override
                public int getNumberOfSendingWorkerThreads() {
                    return 2;
                }
            });
        }

        return cmdProcessor;
    }

    private GerritQueryHandler getGerritQueryHandler() {
        if (gQueryHandler == null) {
            gQueryHandler = new GerritQueryHandler(strHost, port, auth);
        }

        return gQueryHandler;
    }

    /**
     * Runs given Gerrit query with the Gerrit API.
     * 
     * @param query
     *            query to execute
     * @return object returned by the API or {@code null} if there were problems with the query
     * @throws RepositoryException
     *             if something went really wrong
     */
    public List<JSONObject> runGerritQuery(String query) throws RepositoryException {
        List<JSONObject> jsonObjects = null;

        log.debug("Gerrit query: " + query);

        try {
            jsonObjects = getGerritQueryHandler().queryJava(query, true, true, true);
        } catch (SshException e) {
            throw new RepositoryException("SSH connection error", e);
        } catch (IOException e) {
            throw new RepositoryException(e);
        } catch (GerritQueryException e) {
            throw new RepositoryException(e);
        }

        if (jsonObjects == null || jsonObjects.isEmpty()) {
            return null;
        }

        JSONObject setInfo = jsonObjects.get(jsonObjects.size() - 1);

        int rowCount = setInfo.getInt(GerritChangeVO.JSON_KEY_ROWCOUNT);
        log.debug("Gerrit row count: " + rowCount);
        if (rowCount == 0) {
            log.debug("No JSON content to report.");
            return null;
        } else {
            log.debug("JSON content returned: ");
            log.debug(jsonObjects);
        }

        return jsonObjects;
    }

    /**
     * Returns the oldest Gerrit's change (within specific project) that has not
     * been verified yet (Verify flag is set to 0).
     * 
     * @param project
     *            project for which changes should be checked
     * @return the oldest Gerrit's change that has not been verified yet
     * @throws RepositoryException
     */
    public GerritChangeVO getOldestUnverifiedChange(String project, String additionalQueryParams) throws RepositoryException {
        GerritChangeVO result = null;

        // Looks for changes that should be verified
        String query = String.format("project:%s %s", project, additionalQueryParams);
        List<JSONObject> jsonObjects = runGerritQuery(query);

        if (jsonObjects != null) {
            ListIterator<JSONObject> iterator = jsonObjects.listIterator(jsonObjects.size() - 1);
            while (result == null && iterator.hasPrevious()) {
                JSONObject jsonObject = iterator.previous();

                if (jsonObject.containsKey(GerritChangeVO.JSON_KEY_PROJECT)) {
                    GerritChangeVO info = transformJSONObject(jsonObject);
                    result = info;
                }
            }
        }

        return result;
    }

    public GerritChangeVO getChangeByID(String changeID) throws RepositoryException {
        log.debug(String.format("getChangeByID(changeID=%s)...", changeID));

        List<JSONObject> jsonObjects = null;

        jsonObjects = runGerritQuery(String.format("change:%s", changeID));

        if (jsonObjects == null) {
			return null;
		}

        return this.transformJSONObject(jsonObjects.get(0));
    }

    /**
     * Retrieves the change id by the revision number.
     * 
     * @param rev
     *            revision number to check
     * @return gerrit's change for the specified revision
     * @throws RepositoryException
     *             when something went wrong
     */
    public GerritChangeVO getChangeByRevision(String rev) throws RepositoryException {
        log.debug(String.format("getChangeByRevision(rev=%s)...", rev));
        List<JSONObject> jsonObjects = runGerritQuery(String.format("commit:%s", rev));
        if (jsonObjects == null) {
            return null;
        }
        return this.transformJSONObject(jsonObjects.get(0));
    }
    
    public List<GerritChangeVO>
                    getGerritChangeInfo() throws RepositoryException {
        return getGerritChangeInfo(null, 0);
    }

    public List<GerritChangeVO>
                    getGerritChangeInfo(String project) throws RepositoryException {
        return getGerritChangeInfo(project, 0);
    }

    public List<GerritChangeVO>
                    getGerritChangeInfo(String project, int limit,
                                        String... options) throws RepositoryException {
        List<GerritChangeVO> result = new ArrayList<GerritChangeVO>();
        StringBuilder query = new StringBuilder();

        if (project != null) {
            query.append(String.format("project:%s ", project));
        }

        query.append("is:open");

        for (String option : options) {
            query.append(" " + option);
        }

        if (limit > 0) {
            query.append(String.format(" limit:%d", limit));
        }

        log.debug(String.format("getGerritChangeInfo(query = %s)...", query));
        List<JSONObject> jsonObjects = runGerritQuery(query.toString());

        if (jsonObjects != null) {
            log.debug("Query result count: " + jsonObjects.size());

            for (JSONObject j : jsonObjects) {
                if (j.containsKey(GerritChangeVO.JSON_KEY_PROJECT)) {
                    GerritChangeVO info = transformJSONObject(j);
                    result.add(info);
                }
            }
        }

        return result;
    }
    
    private GerritChangeVO transformJSONObject(JSONObject j) throws RepositoryException {
        if (j == null) {
            throw new RepositoryException("No data to parse!");
        }

        log.debug(String.format("transformJSONObject(j=%s)", j));

        GerritChangeVO info = new GerritChangeVO();

        info.setProject(j.getString(GerritChangeVO.JSON_KEY_PROJECT));
        info.setBranch(j.getString(GerritChangeVO.JSON_KEY_BRANCH));
        info.setId(j.getString(GerritChangeVO.JSON_KEY_ID));
        info.setNumber(j.getInt(GerritChangeVO.JSON_KEY_NUMBER));
        info.setSubject(j.getString(GerritChangeVO.JSON_KEY_SUBJECT));

        JSONObject owner = j.getJSONObject(GerritChangeVO.JSON_KEY_OWNER);

        info.setOwnerName(owner.getString(GerritChangeVO.JSON_KEY_OWNER_NAME));
        info.setOwnerEmail(owner.getString(GerritChangeVO.JSON_KEY_OWNER_EMAIL));

        info.setUrl(j.getString(GerritChangeVO.JSON_KEY_URL));

        Integer createdOne = j.getInt(GerritChangeVO.JSON_KEY_CREATED_ON);
        info.setCreatedOn(new Date(createdOne.longValue() * 1000));
        Integer lastUpdate = j.getInt(GerritChangeVO.JSON_KEY_LAST_UPDATE);
        info.setLastUpdate(new Date(lastUpdate.longValue() * 1000));

        info.setOpen(j.getBoolean(GerritChangeVO.JSON_KEY_OPEN));
        info.setStatus(j.getString(GerritChangeVO.JSON_KEY_STATUS));

        JSONObject cp =
            j.getJSONObject(GerritChangeVO.JSON_KEY_CURRENT_PATCH_SET);
        try {
            assignPatchSet(info, cp, true);

            List<JSONObject> patchSets =
                j.getJSONArray(GerritChangeVO.JSON_KEY_PATCH_SET);

            for (JSONObject p : patchSets) {
                assignPatchSet(info, p, false);
            }
        } catch (ParseException e) {
            throw new RepositoryException(e.getMessage());
        }

        log.debug(String.format("Object Transformed change=%s", info.toString()));

        return info;
    }

    private void assignPatchSet(GerritChangeVO info, JSONObject p,
                                boolean isCurrent) throws ParseException {
        log.debug(String.format("Assigning Patchset to: %s", info.toString()));

        PatchSet patch = new PatchSet();

        patch.setNumber(p.getInt(GerritChangeVO.JSON_KEY_PATCH_SET_NUM));
        patch.setRevision(p.getString(GerritChangeVO.JSON_KEY_PATCH_SET_REV));
        patch.setRef(p.getString(GerritChangeVO.JSON_KEY_PATCH_SET_REF));

        JSONObject patchSetUploader = p.getJSONObject(GerritChangeVO.JSON_KEY_PATCH_SET_UPDLOADER);
        patch.setUploaderName(patchSetUploader.getString(
				GerritChangeVO.JSON_KEY_OWNER_SET_UPDLOADER_NAME));
        patch.setUploaderEmail(patchSetUploader.getString(
				GerritChangeVO.JSON_KEY_OWNER_SET_UPDLOADER_EMAIL));

        Integer patchSetCreatedOn = p.getInt(GerritChangeVO.JSON_KEY_PATCH_SET_CREATED_ON);
        patch.setCreatedOn(new Date(patchSetCreatedOn.longValue() * 1000));

        if (p.containsKey(GerritChangeVO.JSON_KEY_PATCH_SET_APPRVS)) {
            List<JSONObject> approvals = p.getJSONArray(GerritChangeVO.JSON_KEY_PATCH_SET_APPRVS);

            for (JSONObject a : approvals) {
                Approval apprv = new Approval();

                apprv.setType(a.getString(GerritChangeVO.JSON_KEY_PATCH_SET_APPRVS_TYPE));

                if (a .containsKey(GerritChangeVO.JSON_KEY_PATCH_SET_APPRVS_BY_EMAIL)) {
                    apprv.setDescription(a.getString(GerritChangeVO.JSON_KEY_PATCH_SET_APPRVS_DESC));
                }

                apprv.setValue(a.getInt(GerritChangeVO.JSON_KEY_PATCH_SET_APPRVS_VALUE));

                Integer grantedOn = a.getInt(GerritChangeVO.JSON_KEY_PATCH_SET_APPRVS_GRANTED_ON);
                apprv.setGrantedOn(new Date(grantedOn.longValue() * 1000));

                JSONObject by = a.getJSONObject(GerritChangeVO.JSON_KEY_PATCH_SET_APPRVS_BY);
                apprv .setByName(by .getString(GerritChangeVO.JSON_KEY_PATCH_SET_APPRVS_BY_NAME));

                if (by.containsKey(GerritChangeVO.JSON_KEY_PATCH_SET_APPRVS_BY_EMAIL)) {
                    apprv .setByEmail(by .getString(GerritChangeVO.JSON_KEY_PATCH_SET_APPRVS_BY_EMAIL));
                }

                if (isCurrent) {
                    if (apprv.getType().equals("VRIF")) {
						info.setVerificationScore(info.getVerificationScore() + apprv.getValue());
					} else if (apprv.getType().equals("CRVW")) {
						info.setReviewScore(info.getReviewScore() + apprv.getValue());
					}
                }

                patch.getApprovals().add(apprv);
            }
        }

        if (p.containsKey(GerritChangeVO.JSON_KEY_PATCH_SET_FILES)) {
            List<JSONObject> fileSets = p.getJSONArray(GerritChangeVO.JSON_KEY_PATCH_SET_FILES);

            for (JSONObject f : fileSets) {
                FileSet fileSet = new FileSet();

                fileSet.setFile(f.getString(GerritChangeVO.JSON_KEY_PATCH_SET_FILES_FILE));
                fileSet.setType(f.getString(GerritChangeVO.JSON_KEY_PATCH_SET_FILES_TYPE));

                patch.getFileSets().add(fileSet);
            }
        }

        if (isCurrent) {
            info.setCurrentPatchSet(patch);
        } else {
            info.getPatchSets().add(patch);
        }

        log.debug(String.format("Patchset assigned: %s", patch.toString()));
    }
}
