/*******************************************************************************
 *
 * Copyright (c) 2004-2011 Oracle Corporation.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *
 * Kohsuke Kawaguchi, Patrick van Dissel (id:pvdissel).
 *
 *******************************************************************************/

package hudson.scm;

import hudson.model.AbstractModelObject;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.triggers.SCMTrigger;

import hudson.util.QueryParameterMap;
import org.apache.commons.io.IOUtils;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.tmatesoft.svn.core.SVNException;

import javax.servlet.ServletException;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import static java.util.logging.Level.*;
import java.util.logging.Logger;

/**
 * Per repository status.
 * <p/>
 * @author Kohsuke Kawaguchi
 * @see SubversionStatus
 */
public class SubversionRepositoryStatus extends AbstractModelObject {

    public final UUID uuid;

    public SubversionRepositoryStatus(UUID uuid) {
        this.uuid = uuid;
    }

    public String getDisplayName() {
        return uuid.toString();
    }

    public String getSearchUrl() {
        return uuid.toString();
    }

    /**
     * Notify the commit to this repository.
     *
     * <p>
     * Because this URL is not guarded, we can't really trust the data that's sent to us. But we intentionally
     * don't protect this URL to simplify <tt>post-commit</tt> script set up.
     */
    public void doNotifyCommit(StaplerRequest req, StaplerResponse rsp) throws ServletException, IOException {
        requirePOST();

        // compute the affected paths
        Set<String> affectedPath = new HashSet<String>();
        String line;
        BufferedReader r = new BufferedReader(req.getReader());

        try {
            while ((line = r.readLine()) != null) {
                if (LOGGER.isLoggable(FINER)) {
                    LOGGER.finer("Reading line: " + line);
                }
                affectedPath.add(line.substring(4));
                if (line.startsWith("svnlook changed --revision ")) {
                    String msg = "Expecting the output from the svnlook command but instead you just sent me the svnlook invocation command line: " + line;
                    LOGGER.warning(msg);
                    throw new IllegalArgumentException(msg);
                }
            }
        } finally {
            IOUtils.closeQuietly(r);
        }

        if (LOGGER.isLoggable(FINE)) {
            LOGGER.fine("Change reported to Subversion repository " + uuid + " on " + affectedPath);
        }
        boolean scmFound = false, triggerFound = false, uuidFound = false, pathFound = false;

        // we can't reliably use req.getParameter() as it can try to parse the payload, which we've already consumed above.
        // servlet container relies on Content-type to decide if it wants to parse the payload or not, and at least
        // in case of Jetty, it doesn't check if the payload is
        QueryParameterMap query = new QueryParameterMap(req);
        String revParam = query.get("rev");
        long rev = -1;
        if (revParam != null) {
            rev = Long.parseLong(revParam);
        } else {
            revParam = req.getHeader("X-Hudson-Subversion-Revision");
            if (revParam != null) {
                rev = Long.parseLong(revParam);
            }
        }

        OUTER:
        for (AbstractProject<?, ?> p : Hudson.getInstance().getItems(AbstractProject.class)) {
            try {
                SCM scm = p.getScm();
                if (scm instanceof SubversionSCM) {
                    scmFound = true;
                } else {
                    continue;
                }

                SCMTrigger trigger = p.getTrigger(SCMTrigger.class);
                if (trigger != null) {
                    triggerFound = true;
                } else {
                    continue;
                }

                SubversionSCM sscm = (SubversionSCM) scm;
                for (SubversionSCM.ModuleLocation loc : sscm.getLocations()) {
                    if (loc.getUUID(p).equals(uuid)) {
                        uuidFound = true;
                    } else {
                        continue;
                    }

                    String m = loc.getSVNURL().getPath();
                    String n = loc.getRepositoryRoot(p).getPath();
                    if (!m.startsWith(n)) {
                        continue;   // repository root should be a subpath of the module path, but be defensive
                    }
                    String remaining = m.substring(n.length());
                    if (remaining.startsWith("/")) {
                        remaining = remaining.substring(1);
                    }
                    String remainingSlash = remaining + '/';

                    final RevisionParameterAction[] actions;
                    if (rev != -1) {
                        SubversionSCM.SvnInfo info[] = {new SubversionSCM.SvnInfo(loc.getURL(), rev)};
                        RevisionParameterAction action = new RevisionParameterAction(info);
                        actions = new RevisionParameterAction[]{action};

                    } else {
                        actions = new RevisionParameterAction[0];
                    }

                    for (String path : affectedPath) {
                        if (path.equals(remaining) /*
                                 * for files
                                 */ || path.startsWith(remainingSlash) /*
                                 * for dirs
                                 */) {
                            // this project is possibly changed. poll now.
                            // if any of the data we used was bogus, the trigger will not detect a change
                            LOGGER.fine("Scheduling the immediate polling of " + p);
                            trigger.run(actions);
                            pathFound = true;

                            continue OUTER;
                        }
                    }
                }
            } catch (SVNException e) {
                LOGGER.log(WARNING, "Failed to handle Subversion commit notification", e);
            }
        }

        if (!scmFound) {
            LOGGER.warning("No subversion jobs found");
        } else if (!triggerFound) {
            LOGGER.warning("No subversion jobs using SCM polling");
        } else if (!uuidFound) {
            LOGGER.warning("No subversion jobs using repository: " + uuid);
        } else if (!pathFound) {
            LOGGER.fine("No jobs found matching the modified files");
        }

        rsp.setStatus(SC_OK);
    }
    private static final Logger LOGGER = Logger.getLogger(SubversionRepositoryStatus.class.getName());
}
