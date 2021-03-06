/*******************************************************************************
 *
 * Copyright (c) 2004-2009 Oracle Corporation.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *
 * Kohsuke Kawaguchi
 *
 *******************************************************************************/
package hudson.scm;

import hudson.model.Hudson;
import hudson.remoting.Callable;
import hudson.remoting.Channel;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.internal.wc.admin.ISVNAdminAreaFactorySelector;
import org.tmatesoft.svn.core.internal.wc.admin.SVNAdminArea14;
import org.tmatesoft.svn.core.internal.wc.admin.SVNAdminAreaFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link ISVNAdminAreaFactorySelector} that uses 1.4 compatible workspace for new check out,
 * but still supports 1.5 workspace, if asked to work with it.
 *
 * <p>
 * Since there are many tools out there that still don't support Subversion 1.5 (including
 * all the major Unix distributions that haven't bundled Subversion 1.5), using 1.4 as the
 * default would reduce the likelihood of the user running into "this SVN client can't work
 * with this workspace version..." problem when using other SVN tools.
 *
 * <p>
 * The primary scenario of this is the use of command-line SVN client, either from shell
 * script, Ant, or Maven.
 *
 * @author Kohsuke Kawaguchi
 */
public class SubversionWorkspaceSelector implements ISVNAdminAreaFactorySelector {
    public SubversionWorkspaceSelector() {
        // don't upgrade the workspace.
        SVNAdminAreaFactory.setUpgradeEnabled(false);
    }

    @SuppressWarnings({"cast", "unchecked"})
    public Collection getEnabledFactories(File path, Collection factories, boolean writeAccess) throws SVNException {
        if(!writeAccess)    // for reading, use all our available factories
            return factories;

        // for writing, use 1.4
        Collection<SVNAdminAreaFactory> enabledFactories = new ArrayList<SVNAdminAreaFactory>();
        for (SVNAdminAreaFactory factory : (Collection<SVNAdminAreaFactory>)factories)
            if (factory.getSupportedVersion() == workspaceFormat)
                enabledFactories.add(factory);

        return enabledFactories;
    }

    /**
     * {@link #getEnabledFactories(File, Collection, boolean)} method is called quite a few times
     * during a Subversion operation, so consulting this value back with master each time is not practical
     * performance wise. Therefore, we have {@link SubversionSCM} set this value, even though it's error prone.
     */
    public static volatile int workspaceFormat = SVNAdminArea14.WC_FORMAT;

    public static void syncWorkspaceFormatFromMaster() {
        Hudson h = Hudson.getInstance();
        if (h!=null)
            workspaceFormat = h.getDescriptorByType(SubversionSCM.DescriptorImpl.class).getWorkspaceFormat();
        else {
            Channel c = Channel.current();
            if (c!=null)    // just being defensive. cannot be null.
                try {
                    workspaceFormat = c.call(new Callable<Integer, RuntimeException>() {
                        public Integer call()  {
                            return Hudson.getInstance().getDescriptorByType(SubversionSCM.DescriptorImpl.class).getWorkspaceFormat();
                        }
                    });
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to retrieve Subversion workspace format",e);
                } catch (InterruptedException e) {
                    LOGGER.log(Level.WARNING, "Failed to retrieve Subversion workspace format",e);
                }
        }
    }

    private static final Logger LOGGER = Logger.getLogger(SubversionWorkspaceSelector.class.getName());
}
