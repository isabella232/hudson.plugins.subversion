package hudson.scm;

import com.google.common.io.Files;
import hudson.ClassicPluginStrategy;
import hudson.Launcher.LocalLauncher;
import hudson.Proc;
import hudson.scm.SubversionSCM.DescriptorImpl;
import hudson.util.IOUtils;
import hudson.util.StreamTaskListener;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Timer;
import java.util.zip.ZipInputStream;
import org.jvnet.hudson.test.HudsonHomeLoader.CopyExisting;
import org.jvnet.hudson.test.HudsonTestCase;
import org.tmatesoft.svn.core.auth.ISVNAuthenticationManager;
import org.tmatesoft.svn.core.wc.SVNWCUtil;

/**
 * Base class for Subversion related tests.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class AbstractSubversionTest extends HudsonTestCase {
    protected DescriptorImpl descriptor;
    protected String kind = ISVNAuthenticationManager.PASSWORD;


    @Override
    protected void setUp() throws Exception {
        //Enable classic plugin strategy, because some extensions are duplicated with default strategy
        System.setProperty("hudson.PluginStrategy", "hudson.ClassicPluginStrategy");
        super.setUp();
        descriptor = hudson.getDescriptorByType(DescriptorImpl.class);
    }

    protected Proc runSvnServe(URL zip) throws Exception {
        return runSvnServe(new CopyExisting(zip).allocate());
    }

    /**
     * Runs svnserve to serve the specified directory as a subversion repository.
     * @throws IOException 
     * @throws InterruptedException 
     */
    protected Proc runSvnServe(File repo) throws IOException, InterruptedException {
        LocalLauncher launcher = new LocalLauncher(new StreamTaskListener(System.out, null));
        try {
            launcher.launch().cmds("svnserve", "--help").start().join();
            Proc proc = launcher.launch().cmds(
            "svnserve", "-d", "--foreground", "-r", repo.getAbsolutePath()).pwd(repo).start();
            if (!proc.isAlive()){
                System.out.println("The process to start the Light weight SVN server is not alive");
                return null;
            }
            return proc;
        } catch (IOException exc) {
            exc.printStackTrace();
            // if we fail to launch svnserve, skip the test
            return null;
        }
         
    }

    protected ISVNAuthenticationManager createInMemoryManager() {
        ISVNAuthenticationManager m = SVNWCUtil.createDefaultAuthenticationManager(hudson.root, null, null, false);
        m.setAuthenticationProvider(descriptor.createAuthenticationProvider(null));
        return m;
    }

    static {
        ClassicPluginStrategy.useAntClassLoader = true;
    }
}
