/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.test.manualmode.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.directory.api.ldap.model.entry.DefaultEntry;
import org.apache.directory.api.ldap.model.ldif.LdifEntry;
import org.apache.directory.api.ldap.model.ldif.LdifReader;
import org.apache.directory.api.ldap.model.schema.SchemaManager;
import org.apache.directory.server.annotations.CreateLdapServer;
import org.apache.directory.server.annotations.CreateTransport;
import org.apache.directory.server.core.annotations.AnnotationUtils;
import org.apache.directory.server.core.annotations.ContextEntry;
import org.apache.directory.server.core.annotations.CreateDS;
import org.apache.directory.server.core.annotations.CreateIndex;
import org.apache.directory.server.core.annotations.CreatePartition;
import org.apache.directory.server.core.api.DirectoryService;
import org.apache.directory.server.core.factory.DSAnnotationProcessor;
import org.apache.directory.server.core.kerberos.KeyDerivationInterceptor;
import org.apache.directory.server.factory.ServerAnnotationProcessor;
import org.apache.directory.server.ldap.LdapServer;
import org.apache.directory.server.ldap.handlers.ssl.LdapsInitializer;
import org.apache.directory.server.protocol.shared.transport.TcpTransport;
import org.apache.directory.server.protocol.shared.transport.Transport;
import org.jboss.arquillian.container.test.api.ContainerController;
import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.junit.InSequence;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.api.ServerSetupTask;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.test.integration.security.common.AbstractSecurityDomainsServerSetupTask;
import org.jboss.as.test.integration.security.common.AbstractSecurityRealmsServerSetupTask;
import org.jboss.as.test.integration.security.common.ManagedCreateLdapServer;
import org.jboss.as.test.integration.security.common.ManagedCreateTransport;
import org.jboss.as.test.integration.security.common.Utils;
import org.jboss.as.test.integration.security.common.config.SecurityDomain;
import org.jboss.as.test.integration.security.common.config.SecurityModule;
import org.jboss.as.test.integration.security.common.config.SecurityModule.Builder;
import org.jboss.as.test.integration.security.common.config.realm.Authentication;
import org.jboss.as.test.integration.security.common.config.realm.Authorization;
import org.jboss.as.test.integration.security.common.config.realm.LdapAuthentication;
import org.jboss.as.test.integration.security.common.config.realm.RealmKeystore;
import org.jboss.as.test.integration.security.common.config.realm.SecurityRealm;
import org.jboss.as.test.integration.security.common.config.realm.ServerIdentity;
import org.jboss.as.test.shared.TestSuiteEnvironment;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * A testcase which tests a SecurityRealm used as a SSL configuration source for LDAPs, asserts that
 * {@code always-send-client-cert} (see <a href="https://issues.jboss.org/browse/WFCORE-2647"></a>) attribute of an outbound
 * LDAP connection works properly.
 * <p>
 * This test uses a simple re-implementation of ApacheDS {@link LdapsInitializer} class, which enables to set our own
 * TrustManager and require the client authentication.<br/>
 * Test scenario:
 * <ol>
 * <li>start container</li>
 * <li>Start LDAP server with LDAPs protocol - use {@link TrustAndStoreTrustManager} as a TrustManager for incoming connections.
 * </li>
 * <li>configure two security realms and two separate LDAP outbound connections for each realm: one of those connections has {@code alwaysSendClientCert(true)} and the other has alwaysSendClientCert(false)</li>
 * <li>configure two security domains which point to the two security realms respectively</li>
 * <li>deploy two web applications, which use the two security domains respectively</li>
 * <li>test access to the web-apps</li>
 * <li>test if the server certificate configured in the security realm was used for client authentication on LDAP server side
 * (use {@link TrustAndStoreTrustManager#isSubjectInClientCertChain(String)})</li>
 * <li>undo the changes</li>
 * </ol>
 *
 * @author Josef Cacek
 * @author <a href="https://github.com/ppalaga">Peter Palaga</a>
 *
 */
@RunWith(Arquillian.class)
@RunAsClient
public class OutboundLdapConnectionClientCertTestCase {

    private static final String KEYSTORE_PASSWORD = "123456";
    private static final String KEYSTORE_FILENAME_LDAPS = "ldaps.keystore";
    private static final String KEYSTORE_FILENAME_JBAS = "jbas.keystore";
    private static final String TRUSTSTORE_FILENAME_JBAS = "jbas.truststore";
    private static final File KEYSTORE_FILE_LDAPS = new File(KEYSTORE_FILENAME_LDAPS);
    private static final File KEYSTORE_FILE_JBAS = new File(KEYSTORE_FILENAME_JBAS);
    private static final File TRUSTSTORE_FILE_JBAS = new File(TRUSTSTORE_FILENAME_JBAS);

    private static final int LDAPS_PORT = 10636;

    private static final String CONTAINER = "default-jbossas";

    private static final String TEST_FILE = "test.txt";
    private static final String TEST_FILE_CONTENT = "OK";

    private static final String LDAPS_AUTHN_REALM_ALWAYS = "ldaps-authn-realm-always";
    private static final String LDAPS_AUTHN_REALM_SOMETIMES = "ldaps-authn-realm-sometimes";
    private static final String LDAPS_AUTHN_SD_ALWAYS = "ldaps-authn-sd-always";
    private static final String LDAPS_AUTHN_SD_SOMETIMES = "ldaps-authn-sd-sometimes";
    private static final String SSL_CONF_REALM = "ssl-conf-realm";
    private static final String LDAPS_CONNECTION_ALWAYS = "test-ldaps-always";
    private static final String LDAPS_CONNECTION_SOMETIMES = "test-ldaps-sometimes";

    private static final String SECURITY_CREDENTIALS = "secret";
    private static final String SECURITY_PRINCIPAL = "uid=admin,ou=system";

    private final LDAPServerSetupTask ldapsSetup = new LDAPServerSetupTask();
    private final SecurityRealmsSetup realmsSetup = new SecurityRealmsSetup();
    private final SecurityDomainsSetup domainsSetup = new SecurityDomainsSetup();
    private static boolean serverConfigured = false;

    @ArquillianResource
    private static ContainerController containerController;

    @ArquillianResource
    Deployer deployer;

    @Before
    public void initializeRoleConfiguration() throws Exception {
        if (containerController.isStarted(CONTAINER) && !serverConfigured) {
            final ModelControllerClient client = TestSuiteEnvironment.getModelControllerClient();
            ManagementClient mgmtClient = new ManagementClient(client, TestSuiteEnvironment.getServerAddress(),
                    TestSuiteEnvironment.getServerPort(), "http-remoting");
            prepareServer(mgmtClient);
        }
    }

    @After
    public void restoreRoleConfiguration() throws Exception {
        if (serverConfigured && containerController.isStarted(CONTAINER)) {
            final ModelControllerClient client = TestSuiteEnvironment.getModelControllerClient();
            ManagementClient mgmtClient = new ManagementClient(client, TestSuiteEnvironment.getServerAddress(),
                    TestSuiteEnvironment.getServerPort(), "http-remoting");
            cleanUpServer(mgmtClient);
        }
    }

    public void prepareServer(ManagementClient mgmtClient) throws Exception {
        serverConfigured = true;
        createTempKS(KEYSTORE_FILENAME_LDAPS, KEYSTORE_FILE_LDAPS);
        createTempKS(KEYSTORE_FILENAME_JBAS, KEYSTORE_FILE_JBAS);
        createTempKS(TRUSTSTORE_FILENAME_JBAS, TRUSTSTORE_FILE_JBAS);
        ldapsSetup.startLdapServer();
        realmsSetup.setup(mgmtClient, CONTAINER);
        domainsSetup.setup(mgmtClient, CONTAINER);
    }

    private void createTempKS(final String keystoreFilename, final File keystoreFile) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(keystoreFilename);
                FileOutputStream fos = new FileOutputStream(keystoreFile)) {
            IOUtils.copy(is, fos);
        }
    }

    public void cleanUpServer(ManagementClient mgmtClient) throws Exception {
        KEYSTORE_FILE_LDAPS.delete();
        KEYSTORE_FILE_JBAS.delete();
        TRUSTSTORE_FILE_JBAS.delete();
        realmsSetup.tearDown(mgmtClient, CONTAINER);
        ldapsSetup.shutdownLdapServer();
        domainsSetup.tearDown(mgmtClient, CONTAINER);
    }

    @Deployment(name = LDAPS_AUTHN_SD_ALWAYS, managed = false, testable = false)
    public static WebArchive deploymentAlways() {
        return createDeployment(LDAPS_AUTHN_SD_ALWAYS);
    }

    @Deployment(name = LDAPS_AUTHN_SD_SOMETIMES, managed = false, testable = false)
    public static WebArchive deploymentSometimes() {
        return createDeployment(LDAPS_AUTHN_SD_SOMETIMES);
    }

    @Test
    @InSequence(-2)
    public void startContainer() throws Exception {
        containerController.start(CONTAINER);
    }

    @Test
    @InSequence(0)
    @OperateOnDeployment(LDAPS_AUTHN_SD_ALWAYS)
    public void test(@ArquillianResource ManagementClient mgmtClient) throws Exception {
        final TrustAndStoreTrustManager trustManager = ldapsSetup.trustAndStoreTrustManager;
        try {
            deployer.deploy(LDAPS_AUTHN_SD_ALWAYS);
            deployer.deploy(LDAPS_AUTHN_SD_SOMETIMES);

            trustManager.clear();
            final URL appUrlSometimes = new URL(mgmtClient.getWebUri().toString() + "/" + LDAPS_AUTHN_SD_SOMETIMES + "/" + TEST_FILE);
            Utils.makeCallWithBasicAuthn(appUrlSometimes, "jduke", "bad_password", HttpServletResponse.SC_UNAUTHORIZED);
            assertEquals("Number of certificates stored in TrustAndStoreTrustManager", 1, trustManager.getCertCount());

            trustManager.clear();
            Utils.makeCallWithBasicAuthn(appUrlSometimes, "jduke", "theduke", HttpServletResponse.SC_UNAUTHORIZED);
            assertEquals("Number of certificates stored in TrustAndStoreTrustManager", 1, trustManager.getCertCount());


            trustManager.clear();
            final URL appUrlAlways = new URL(mgmtClient.getWebUri().toString() + "/" + LDAPS_AUTHN_SD_ALWAYS + "/" + TEST_FILE);
            Utils.makeCallWithBasicAuthn(appUrlAlways, "jduke", "bad_password", HttpServletResponse.SC_UNAUTHORIZED);
            assertEquals("Number of certificates stored in TrustAndStoreTrustManager", 1, trustManager.getCertCount());

            trustManager.clear();
            final String resp = Utils.makeCallWithBasicAuthn(appUrlAlways, "jduke", "theduke", HttpServletResponse.SC_OK);
            assertEquals(TEST_FILE_CONTENT, resp);
            assertTrue("Certificate (client key) from SecurityRealm was not used.",
                    trustManager.isSubjectInClientCertChain("CN=JBAS"));
            assertEquals("Number of certificates stored in TrustAndStoreTrustManager", 1, trustManager.getCertCount());
        } finally {
            trustManager.clear();
            deployer.undeploy(LDAPS_AUTHN_SD_SOMETIMES);
            deployer.undeploy(LDAPS_AUTHN_SD_ALWAYS);
        }

    }

    @Test
    @InSequence(2)
    public void stopContainer() throws Exception {
        containerController.stop(CONTAINER);
    }
    // Private methods -------------------------------------------------------

    /**
     * Creates test application for this TestCase.
     *
     * @param securityDomain security domain name, also used as an application name
     * @return
     */
    private static WebArchive createDeployment(final String securityDomain) {
        final WebArchive war = ShrinkWrap.create(WebArchive.class, securityDomain + ".war");
        war.add(new StringAsset(TEST_FILE_CONTENT), TEST_FILE);
        war.addAsWebInfResource(OutboundLdapConnectionTestCase.class.getPackage(), OutboundLdapConnectionTestCase.class
                .getSimpleName() + "-web.xml", "web.xml");
        war.addAsWebInfResource(Utils.getJBossWebXmlAsset(securityDomain), "jboss-web.xml");
        war.addAsResource(new StringAsset("jduke=Admin"), "roles.properties");
        return war;
    }

    /**
     * A {@link ServerSetupTask} instance which creates security domains for this test case.
     *
     * @author Josef Cacek
     */
    static class SecurityDomainsSetup extends AbstractSecurityDomainsServerSetupTask {

        /**
         * Returns SecurityDomains configuration for this testcase.
         *
         * @see org.jboss.as.test.integration.security.common.AbstractSecurityDomainsServerSetupTask#getSecurityDomains()
         */
        @Override
        protected SecurityDomain[] getSecurityDomains() {
            final Builder realmDirectLMBuilder = new SecurityModule.Builder().name("RealmDirect");
            final SecurityModule mappingModule = new SecurityModule.Builder().name("SimpleRoles").putOption("jduke", "Admin")
                    .build();

            final SecurityDomain sdAlways = new SecurityDomain.Builder().name(LDAPS_AUTHN_SD_ALWAYS)
                    .loginModules(realmDirectLMBuilder.putOption("realm", LDAPS_AUTHN_REALM_ALWAYS).build())
                    .mappingModules(mappingModule).build();
            final SecurityDomain sdSometimes = new SecurityDomain.Builder().name(LDAPS_AUTHN_SD_SOMETIMES)
                    .loginModules(realmDirectLMBuilder.putOption("realm", LDAPS_AUTHN_REALM_SOMETIMES).build())
                    .mappingModules(mappingModule).build();
            return new SecurityDomain[]{sdAlways, sdSometimes};
        }
    }

    /**
     * A {@link ServerSetupTask} instance which creates security realms for this test case.
     *
     * @author Josef Cacek
     */
    static class SecurityRealmsSetup extends AbstractSecurityRealmsServerSetupTask {

        /**
         * Returns SecurityRealms configuration for this testcase.
         */
        @Override
        protected SecurityRealm[] getSecurityRealms() {
            final RealmKeystore.Builder keyStoreBuilder = new RealmKeystore.Builder().keystorePassword(KEYSTORE_PASSWORD);
            final String ldapsUrl = "ldaps://" + Utils.getSecondaryTestAddress(managementClient) + ":" + LDAPS_PORT;

            final SecurityRealm sslConfRealm = new SecurityRealm.Builder()
                    .name(SSL_CONF_REALM)
                    .authentication(
                            new Authentication.Builder()
                            .truststore(keyStoreBuilder.keystorePath(TRUSTSTORE_FILE_JBAS.getAbsolutePath()).build())
                            .build()
                    )
                    .serverIdentity(
                            new ServerIdentity.Builder()
                            .ssl(keyStoreBuilder.keystorePath(KEYSTORE_FILE_JBAS.getAbsolutePath()).build())
                            .build()
                    )
                    .build();
            final SecurityRealm ldapsAuthRealmAlways = new SecurityRealm.Builder()
                    .name(LDAPS_AUTHN_REALM_ALWAYS)
                    .authentication(
                            new Authentication.Builder()
                            .ldap(
                                    new LdapAuthentication.Builder()
                                    // shared attributes
                                    .connection(LDAPS_CONNECTION_ALWAYS)
                                    // ldap-connection
                                    .url(ldapsUrl)
                                    .searchDn(SECURITY_PRINCIPAL)
                                    .searchCredential(SECURITY_CREDENTIALS)
                                    .securityRealm(SSL_CONF_REALM)
                                    .alwaysSendClientCert(true)
                                    // ldap authentication
                                    .baseDn("ou=People,dc=jboss,dc=org")
                                    .recursive(Boolean.TRUE)
                                    .usernameAttribute("uid")
                                    .build()
                            )
                            .build()
                    )
                    .authorization(
                            new Authorization.Builder()
                            .path("application-roles.properties")
                            .relativeTo("jboss.server.config.dir")
                            .build()
                    )
                    .build();
            final SecurityRealm ldapsAuthRealmSometimes = new SecurityRealm.Builder()
                    .name(LDAPS_AUTHN_REALM_SOMETIMES)
                    .authentication(
                            new Authentication.Builder()
                            .ldap(
                                    new LdapAuthentication.Builder()
                                    // shared attributes
                                    .connection(LDAPS_CONNECTION_SOMETIMES)
                                    // ldap-connection
                                    .url(ldapsUrl)
                                    .searchDn(SECURITY_PRINCIPAL)
                                    .searchCredential(SECURITY_CREDENTIALS)
                                    .securityRealm(SSL_CONF_REALM)
                                    // ldap authentication
                                    .baseDn("ou=People,dc=jboss,dc=org")
                                    .recursive(Boolean.TRUE)
                                    .usernameAttribute("uid")
                                    .build()
                            )
                            .build()
                    )
                    .authorization(
                            new Authorization.Builder()
                            .path("application-roles.properties")
                            .relativeTo("jboss.server.config.dir")
                            .build()
                    )
                    .build();
            return new SecurityRealm[]{sslConfRealm, ldapsAuthRealmAlways, ldapsAuthRealmSometimes};
        }
    }

    /**
     * A server setup task which configures and starts LDAP server.
     */
    //@formatter:off
    @CreateDS(
            name = "JBossDS-OutboundLdapConnectionClientCertTestCase",
            factory = org.jboss.as.test.integration.ldap.InMemoryDirectoryServiceFactory.class,
            partitions = {
                @CreatePartition(
                name = "jboss",
                suffix = "dc=jboss,dc=org",
                contextEntry = @ContextEntry(
                entryLdif = "dn: dc=jboss,dc=org\n"
                + "dc: jboss\n"
                + "objectClass: top\n"
                + "objectClass: domain\n\n"),
                indexes = {
                    @CreateIndex(attribute = "objectClass"),
                    @CreateIndex(attribute = "dc"),
                    @CreateIndex(attribute = "ou")
                }
                )
            },
            additionalInterceptors = {KeyDerivationInterceptor.class})
    @CreateLdapServer(
            transports = {
                @CreateTransport(protocol = "LDAPS", port = LDAPS_PORT, address = "0.0.0.0")
            },
            certificatePassword = KEYSTORE_PASSWORD)
    //@formatter:on
    static class LDAPServerSetupTask {

        private DirectoryService directoryService;
        private LdapServer ldapServer;
        private final TrustAndStoreTrustManager trustAndStoreTrustManager = new TrustAndStoreTrustManager(OutboundLdapConnectionClientCertTestCase.class.getSimpleName());

        /**
         * Creates directory services, starts LDAP server and KDCServer.
         */
        public void startLdapServer() throws Exception {
            LdapsInitializer.setAndLockTrustManager(trustAndStoreTrustManager);
            directoryService = DSAnnotationProcessor.getDirectoryService();
            final SchemaManager schemaManager = directoryService.getSchemaManager();
            try (LdifReader ldifReader = new LdifReader(OutboundLdapConnectionClientCertTestCase.class.getResourceAsStream(
                    "OutboundLdapConnectionTestCase.ldif"))) {
                for (LdifEntry ldifEntry : ldifReader) {
                    directoryService.getAdminSession().add(new DefaultEntry(schemaManager, ldifEntry.getEntry()));
                }
            }
            final ManagedCreateLdapServer createLdapServer = new ManagedCreateLdapServer(
                    (CreateLdapServer) AnnotationUtils.getInstance(CreateLdapServer.class));
            createLdapServer.setKeyStore(KEYSTORE_FILE_LDAPS.getAbsolutePath());
            fixTransportAddress(createLdapServer, StringUtils.strip(TestSuiteEnvironment.getSecondaryTestAddress(false)));
            ldapServer = ServerAnnotationProcessor.instantiateLdapServer(createLdapServer, directoryService);

            /* set setNeedClientAuth(true) and setWantClientAuth(true) manually as there is no way to do this via annotation */
            Transport[] transports = ldapServer.getTransports();
            assertTrue("The LDAP server configured via annotations should have just one transport", transports.length == 1);
            final TcpTransport transport = (TcpTransport) transports[0];
            transport.setNeedClientAuth(true);
            transport.setWantClientAuth(true);
            TcpTransport newTransport = new InitializedTcpTransport(transport);
            ldapServer.setTransports(newTransport);

            assertEquals(ldapServer.getCertificatePassword(),KEYSTORE_PASSWORD);
            ldapServer.start();
        }

        /**
         * Fixes bind address in the CreateTransport annotation.
         *
         * @param createLdapServer
         */
        private void fixTransportAddress(ManagedCreateLdapServer createLdapServer, String address) {
            final CreateTransport[] createTransports = createLdapServer.transports();
            for (int i = 0; i < createTransports.length; i++) {
                final ManagedCreateTransport mgCreateTransport = new ManagedCreateTransport(createTransports[i]);
                mgCreateTransport.setAddress(address);
                createTransports[i] = mgCreateTransport;
            }
        }

        /**
         * Stops LDAP server and KDCServer and shuts down the directory service.
         */
        public void shutdownLdapServer() throws Exception {
            ldapServer.stop();
            directoryService.shutdown();
            FileUtils.deleteDirectory(directoryService.getInstanceLayout().getInstanceDirectory());
            LdapsInitializer.unsetAndUnlockTrustManager();
        }
    }
}
