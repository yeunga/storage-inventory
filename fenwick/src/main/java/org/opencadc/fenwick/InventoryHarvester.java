/*
************************************************************************
*******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
**************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
*
*  (c) 2020.                            (c) 2020.
*  Government of Canada                 Gouvernement du Canada
*  National Research Council            Conseil national de recherches
*  Ottawa, Canada, K1A 0R6              Ottawa, Canada, K1A 0R6
*  All rights reserved                  Tous droits réservés
*
*  NRC disclaims any warranties,        Le CNRC dénie toute garantie
*  expressed, implied, or               énoncée, implicite ou légale,
*  statutory, of any kind with          de quelque nature que ce
*  respect to the software,             soit, concernant le logiciel,
*  including without limitation         y compris sans restriction
*  any warranty of merchantability      toute garantie de valeur
*  or fitness for a particular          marchande ou de pertinence
*  purpose. NRC shall not be            pour un usage particulier.
*  liable in any event for any          Le CNRC ne pourra en aucun cas
*  damages, whether direct or           être tenu responsable de tout
*  indirect, special or general,        dommage, direct ou indirect,
*  consequential or incidental,         particulier ou général,
*  arising from the use of the          accessoire ou fortuit, résultant
*  software.  Neither the name          de l'utilisation du logiciel. Ni
*  of the National Research             le nom du Conseil National de
*  Council of Canada nor the            Recherches du Canada ni les noms
*  names of its contributors may        de ses  participants ne peuvent
*  be used to endorse or promote        être utilisés pour approuver ou
*  products derived from this           promouvoir les produits dérivés
*  software without specific prior      de ce logiciel sans autorisation
*  written permission.                  préalable et particulière
*                                       par écrit.
*
*  This file is part of the             Ce fichier fait partie du projet
*  OpenCADC project.                    OpenCADC.
*
*  OpenCADC is free software:           OpenCADC est un logiciel libre ;
*  you can redistribute it and/or       vous pouvez le redistribuer ou le
*  modify it under the terms of         modifier suivant les termes de
*  the GNU Affero General Public        la “GNU Affero General Public
*  License as published by the          License” telle que publiée
*  Free Software Foundation,            par la Free Software Foundation
*  either version 3 of the              : soit la version 3 de cette
*  License, or (at your option)         licence, soit (à votre gré)
*  any later version.                   toute version ultérieure.
*
*  OpenCADC is distributed in the       OpenCADC est distribué
*  hope that it will be useful,         dans l’espoir qu’il vous
*  but WITHOUT ANY WARRANTY;            sera utile, mais SANS AUCUNE
*  without even the implied             GARANTIE : sans même la garantie
*  warranty of MERCHANTABILITY          implicite de COMMERCIALISABILITÉ
*  or FITNESS FOR A PARTICULAR          ni d’ADÉQUATION À UN OBJECTIF
*  PURPOSE.  See the GNU Affero         PARTICULIER. Consultez la Licence
*  General Public License for           Générale Publique GNU Affero
*  more details.                        pour plus de détails.
*
*  You should have received             Vous devriez avoir reçu une
*  a copy of the GNU Affero             copie de la Licence Générale
*  General Public License along         Publique GNU Affero avec
*  with OpenCADC.  If not, see          OpenCADC ; si ce n’est
*  <http://www.gnu.org/licenses/>.      pas le cas, consultez :
*                                       <http://www.gnu.org/licenses/>.
*
************************************************************************
*/

package org.opencadc.fenwick;

import ca.nrc.cadc.auth.AuthenticationUtil;
import ca.nrc.cadc.auth.NotAuthenticatedException;
import ca.nrc.cadc.auth.SSLUtil;
import ca.nrc.cadc.date.DateUtil;
import ca.nrc.cadc.db.DBUtil;
import ca.nrc.cadc.db.TransactionManager;
import ca.nrc.cadc.io.ByteLimitExceededException;
import ca.nrc.cadc.io.ResourceIterator;
import ca.nrc.cadc.net.ExpectationFailedException;
import ca.nrc.cadc.net.PreconditionFailedException;
import ca.nrc.cadc.net.ResourceAlreadyExistsException;
import ca.nrc.cadc.net.ResourceNotFoundException;
import ca.nrc.cadc.net.TransientException;
import ca.nrc.cadc.reg.Capabilities;
import ca.nrc.cadc.reg.Capability;
import ca.nrc.cadc.reg.Standards;
import ca.nrc.cadc.reg.client.RegistryClient;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.security.AccessControlException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.text.DateFormat;
import java.util.Date;
import java.util.Map;
import javax.security.auth.Subject;
import javax.sql.DataSource;
import org.apache.log4j.Logger;
import org.opencadc.inventory.Artifact;
import org.opencadc.inventory.DeletedArtifactEvent;
import org.opencadc.inventory.DeletedStorageLocationEvent;
import org.opencadc.inventory.InventoryUtil;
import org.opencadc.inventory.SiteLocation;
import org.opencadc.inventory.StorageSite;
import org.opencadc.inventory.db.ArtifactDAO;
import org.opencadc.inventory.db.DeletedArtifactEventDAO;
import org.opencadc.inventory.db.EntityNotFoundException;
import org.opencadc.inventory.db.HarvestState;
import org.opencadc.inventory.db.HarvestStateDAO;
import org.opencadc.inventory.db.StorageSiteDAO;
import org.opencadc.inventory.db.version.InitDatabase;
import org.opencadc.inventory.util.ArtifactSelector;
import org.opencadc.tap.TapClient;


/**
 * @author pdowler
 */
public class InventoryHarvester implements Runnable {

    private static final Logger log = Logger.getLogger(InventoryHarvester.class);
    public static final String CERTIFICATE_FILE_LOCATION = System.getProperty("user.home") + "/.ssl/cadcproxy.pem";
    public static final int INITIAL_SLEEP_TIMEOUT = 20;

    private final ArtifactDAO artifactDAO;
    private final URI resourceID;
    private final Capability inventoryTAP;
    private final ArtifactSelector selector;
    private final boolean trackSiteLocations;
    private final HarvestStateDAO harvestStateDAO;
    private final int retriesTimeout;
    private int errorCount = 0;

    /**
     * Constructor.
     *
     * @param daoConfig          config map to pass to cadc-inventory-db DAO classes
     * @param resourceID         identifier for the remote query service
     * @param selector           selector implementation
     * @param trackSiteLocations Whether to track the remote storage site and add it to the Artifact being processed.
     * @param retriesTimeout     time in seconds to keep retrying after an error during processing.
     */
    public InventoryHarvester(Map<String, Object> daoConfig, URI resourceID, ArtifactSelector selector,
                              boolean trackSiteLocations, int retriesTimeout) {
        InventoryUtil.assertNotNull(InventoryHarvester.class, "daoConfig", daoConfig);
        InventoryUtil.assertNotNull(InventoryHarvester.class, "resourceID", resourceID);
        InventoryUtil.assertNotNull(InventoryHarvester.class, "selector", selector);
        this.artifactDAO = new ArtifactDAO(false);
        artifactDAO.setConfig(daoConfig);
        this.resourceID = resourceID;
        this.selector = selector;
        this.trackSiteLocations = trackSiteLocations;
        this.retriesTimeout = retriesTimeout;
        this.harvestStateDAO = new HarvestStateDAO(artifactDAO);
        
        try {
            String jndiDataSourceName = (String) daoConfig.get("jndiDataSourceName");
            String database = (String) daoConfig.get("database");
            String schema = (String) daoConfig.get("schema");
            DataSource ds = DBUtil.findJNDIDataSource(jndiDataSourceName);
            InitDatabase init = new InitDatabase(ds, database, schema);
            init.doInit();
            log.info("initDatabase: " + jndiDataSourceName + " " + schema + " OK");
        } catch (Exception ex) {
            throw new IllegalStateException("check/init database failed", ex);
        }

        try {
            RegistryClient rc = new RegistryClient();
            Capabilities caps = rc.getCapabilities(resourceID);
            // above call throws IllegalArgumentException... should be ResourceNotFoundException but out of scope to fix
            this.inventoryTAP = caps.findCapability(Standards.TAP_10);
            if (inventoryTAP == null) {
                throw new IllegalArgumentException(
                        "invalid config: remote query service " + resourceID + " does not implement "
                        + Standards.TAP_10);
            }
        } catch (ResourceNotFoundException ex) {
            throw new IllegalArgumentException("query service not found: " + resourceID, ex);
        } catch (IOException ex) {
            throw new IllegalArgumentException("invalid config", ex);
        }
    }

    // general behaviour is that this process runs continually and manages it's own schedule
    // - harvest everything up to *now*
    // - go idle for a dynamically determined amount of time
    // - repeat until fail/killed

    /**
     *
     */
    @Override
    public void run() {
        boolean retry = false;
        int retryCount = 1;
        int sleepSeconds = INITIAL_SLEEP_TIMEOUT;

        while (true) {
            if (retry) {
                if (sleepSeconds > this.retriesTimeout) {
                    throw new RuntimeException(String.format("Exiting, retries timeout of %s seconds exceeded",
                                                             this.retriesTimeout));
                }
                try {
                    Thread.sleep(sleepSeconds * 1000L);
                } catch (InterruptedException ex) {
                    throw new RuntimeException("thread sleep interrupted");
                }
                retryCount++;
                sleepSeconds += sleepSeconds;
            }

            try {
                final Subject subject = SSLUtil.createSubject(new File(CERTIFICATE_FILE_LOCATION));
                Subject.doAs(subject, (PrivilegedExceptionAction<Void>) () -> {
                    doit();
                    return null;
                });

                // TODO: dynamic depending on how rapidly the remote content is changing
                // ... this value and the reprocess-last-N-seconds should be related
                long dt = 60 * 1000L;
                Thread.sleep(dt);

                // successful run, reset retry values
                retry = false;
                retryCount = 1;
                sleepSeconds = INITIAL_SLEEP_TIMEOUT;
            } catch (PrivilegedActionException privilegedActionException) {
                // Get the check exceptions in PrivilegedActionException thrown by doit()
                final Exception cause = privilegedActionException.getException();
                if (cause instanceof NoSuchAlgorithmException) {
                    // Artifact checksum algorithm not found, bug, fail.
                    logExit("checksum algorithm not found", cause.getMessage());
                    throw new RuntimeException(cause.getMessage(), cause);
                } else if (cause instanceof ResourceAlreadyExistsException) {
                    // 409 conflict, should not occur, retry.
                    logRetry(retryCount, sleepSeconds, "409 resource exists", cause.getMessage());
                } else if (cause instanceof ByteLimitExceededException) {
                    // 413 entity too large, retry.
                    logRetry(retryCount, sleepSeconds, "413 byte limit exceeded", cause.getMessage());
                } else if (cause instanceof ResourceNotFoundException) {
                    // 404 service not found by TapClient, retry.
                    logRetry(retryCount, sleepSeconds, "404 resource not found", cause.getMessage());
                } else if (cause instanceof TransientException) {
                    //  503 transient, retry.
                    logRetry(retryCount, sleepSeconds, "503 transient", cause.getMessage());
                } else if (cause instanceof IOException) {
                    // IO error talking to service, retry.
                    logRetry(retryCount, sleepSeconds, "IO error", cause.getMessage());
                }
                retry = true;
            } catch (IllegalArgumentException ex) {
                // Be careful here.  This IllegalArgumentException is being caught to work around a mysterious
                // case where TCP connections are simply dropped and the incoming stream of data is invalid.
                // This catch will allow Fenwick to restart its processing.
                // jenkinsd 2020.09.25
                final String message = ex.getMessage().trim();
                if (message.startsWith("wrong number of columns")) {
                    log.error("\n\n*******\n");
                    log.error("Caught IllegalArgumentException - " + message + " (" + ++errorCount + ")");
                    log.error("Ignoring error as presumed to be a dropped connection before fully reading the stream.");
                    log.error("\n*******\n");
                } else if (message.startsWith("invalid checksum URI:")) {
                    log.error("\n\n*******\n");
                    log.error("Caught IllegalArgumentException - " + message + " (" + ++errorCount + ")");
                    log.error("Ignoring error as presumed to be a dropped connection before fully reading the stream.");
                    log.error("CAUTION! - This could actually be a bad MD5 checksum! Logging this to provide an audit.");
                    log.error("\n*******\n");
                } else {
                    // Service 400 error, retry.
                    retry = true;
                    logRetry(retryCount, sleepSeconds, "400 bad request", ex.getMessage());
                }
            } catch (NotAuthenticatedException ex) {
                // 401 not authorized, cert error on host or service, fail.
                logExit("401 not authorized", ex.getMessage());
                throw new RuntimeException(ex.getMessage(), ex);
            } catch (AccessControlException ex) {
                // 403 forbidden, cert error on host or service, fail.
                logExit("403 forbidden", ex.getMessage());
                throw new RuntimeException(ex.getMessage(), ex);
            } catch (InterruptedException ex) {
                // Thread interrupted during sleep, retry.
                logExit("thread sleep interrupted", ex.getMessage());
                throw new RuntimeException(ex.getMessage(), ex);
            } catch (PreconditionFailedException ex) {
                // 412 precondition failed, retry.
                retry = true;
                logRetry(retryCount, sleepSeconds, "412 precondition failed", ex.getMessage());
            } catch (ExpectationFailedException ex) {
                // 417 expectation failed, retry
                retry = true;
                logRetry(retryCount, sleepSeconds, "417 expectation failed", ex.getMessage());
            } catch (RuntimeException ex) {
                // 500 internal service error, retry.
                retry = true;
                logRetry(retryCount, sleepSeconds, "500 runtime error", ex.getMessage());
            }
        }
    }

    // use TapClient to get remote StorageSite record and store it locally
    // - only global inventory needs to track remote StorageSite(s); should be exactly one (minoc) record at a storage site
    // - keep the StorageSite UUID for creating SiteLocation objects below

    // get tracked progress (latest timestamp seen from any iterator)
    // - the head of an iterator is volatile: events can be inserted that are slightly out of monotonic time sequence
    // - actions taken while processing are idempotent
    // - therefore: it should be OK to re-process the last N seconds (eg startTime = curLastModified - 120 seconds)

    // use TapClient to open multiple iterator streams: Artifact, DeletedArtifactEvent, DeletedStorageLocationEvent
    // - incremental: use latest timestamp from above for all iterators
    // - selective: use ArtifactSelector to modify the query for Iterator<Artifact>

    // process multiple iterators in timestamp order
    // - taking appropriate action for each track progress
    // - stop iterating when reaching a timestamp that exceeds the timestamp when query executed

    /**
     * Perform a sync for Artifacts that match the include constraints in this harvester's selector.
     * @throws ResourceNotFoundException For any missing required configuration that is missing.
     * @throws IOException               For unreadable configuration files.
     * @throws IllegalStateException     For any invalid configuration.
     * @throws TransientException        temporary failure of TAP service: same call could work in future
     * @throws InterruptedException      thread interrupted
     * @throws NoSuchAlgorithmException  If the MessageDigest for synchronizing Artifacts cannot be used.
     */
    void doit() throws ResourceNotFoundException, IOException, IllegalStateException, TransientException,
                       InterruptedException, NoSuchAlgorithmException {
        final TapClient tapClient = new TapClient<>(this.resourceID);
        final Date end = new Date();
        
        final StorageSite storageSite;
        if (trackSiteLocations) {
            
            final StorageSiteDAO storageSiteDAO = new StorageSiteDAO(this.artifactDAO);
            final StorageSiteSync storageSiteSync = new StorageSiteSync(tapClient, storageSiteDAO);
            storageSite = storageSiteSync.doit();

            syncDeletedStorageLocationEvents(tapClient, end, storageSite);
        } else {
            storageSite = null;
        }

        syncDeletedArtifactEvents(tapClient, end);
        syncArtifacts(tapClient, end, storageSite);
    }

    /**
     * Perform a sync for deleted storage locations.  This will be used by Global sites to sync from storage sites to
     * indicate that an Artifact at the given storage site is no longer available.
     * @param tapClient                  A TAP client to issue a Luskan request.
     * @param endDate                    The upper bound of the Luskan query.
     * @param storageSite                The storage site obtained from the Storage Site sync.  Cannot be null.
     * @throws ResourceNotFoundException For any missing required configuration that is missing.
     * @throws IOException               For unreadable configuration files.
     * @throws IllegalStateException     For any invalid configuration.
     * @throws TransientException        temporary failure of TAP service: same call could work in future
     * @throws InterruptedException      thread interrupted
     */
    private void syncDeletedStorageLocationEvents(final TapClient tapClient, final Date endDate,
                                                  final StorageSite storageSite)
            throws ResourceNotFoundException, IOException, IllegalStateException, TransientException,
                   InterruptedException {

        final HarvestState harvestState = this.harvestStateDAO.get(DeletedStorageLocationEvent.class.getName(),
                                                                           this.resourceID);

        DateFormat df = DateUtil.getDateFormat(DateUtil.IVOA_DATE_FORMAT, DateUtil.UTC);

        SSLUtil.renewSubject(AuthenticationUtil.getCurrentSubject(), new File(CERTIFICATE_FILE_LOCATION));

        final DeletedStorageLocationEventSync deletedStorageLocationEventSync =
                new DeletedStorageLocationEventSync(tapClient);
        deletedStorageLocationEventSync.startTime = harvestState.curLastModified;
        deletedStorageLocationEventSync.endTime = endDate;

        final TransactionManager transactionManager = artifactDAO.getTransactionManager();
        
        String start = null;
        if (harvestState.curLastModified != null) {
            start = df.format(harvestState.curLastModified);
        }
        log.info("QUERY: DeletedStorageLocationEvent from=" + start);
        boolean first = true;
        try (final ResourceIterator<DeletedStorageLocationEvent> deletedStorageLocationEventResourceIterator =
                     deletedStorageLocationEventSync.getEvents()) {

            while (deletedStorageLocationEventResourceIterator.hasNext()) {
                final DeletedStorageLocationEvent deletedStorageLocationEvent =
                        deletedStorageLocationEventResourceIterator.next();
                if (first
                        && deletedStorageLocationEvent.getID().equals(harvestState.curID)
                        && deletedStorageLocationEvent.getLastModified().equals(harvestState.curLastModified)) {
                    log.debug("SKIP: previously processed: " + deletedStorageLocationEvent.getID());
                    first = false;
                    continue; // ugh
                }

                Artifact artifact = this.artifactDAO.get(deletedStorageLocationEvent.getID());
                if (artifact != null) {
                    try {
                        transactionManager.startTransaction();
                        try {
                            artifactDAO.lock(artifact);
                            artifact = artifactDAO.get(deletedStorageLocationEvent.getID());
                        } catch (EntityNotFoundException ex) {
                            artifact = null;
                        }
                        if (artifact != null) {
                            artifact = this.artifactDAO.get(deletedStorageLocationEvent.getID());

                            final SiteLocation siteLocation = new SiteLocation(storageSite.getID());
                            // TODO: this message could also log the artifact and site that was removed
                            log.info("PUT: DeletedStorageLocationEvent " + deletedStorageLocationEvent.getID()
                                     + " " + df.format(deletedStorageLocationEvent.getLastModified()));
                            artifactDAO.removeSiteLocation(artifact, siteLocation);
                            harvestState.curLastModified = deletedStorageLocationEvent.getLastModified();
                            harvestStateDAO.put(harvestState);
                        }

                        transactionManager.commitTransaction();
                    } catch (Exception exception) {
                        if (transactionManager.isOpen()) {
                            log.error("Exception in transaction.  Rolling back...");
                            transactionManager.rollbackTransaction();
                            log.error("Rollback: OK");
                        }

                        throw exception;
                    } finally {
                        if (transactionManager.isOpen()) {
                            log.error("BUG: transaction open in finally. Rolling back...");
                            transactionManager.rollbackTransaction();
                            log.error("Rollback: OK");
                            throw new RuntimeException("BUG: transaction open in finally");
                        }
                    }
                }
            }
        } 
    }

    /**
     * Perform a sync of the remote deleted artifact events.
     * @param tapClient                  A TAP client to issue a Luskan request.
     * @param endDate                    The upper bound of the Luskan query.
     * @throws ResourceNotFoundException For any missing required configuration that is missing.
     * @throws IOException               For unreadable configuration files.
     * @throws IllegalStateException     For any invalid configuration.
     * @throws TransientException        temporary failure of TAP service: same call could work in future
     * @throws InterruptedException      thread interrupted
     */
    private void syncDeletedArtifactEvents(final TapClient tapClient, final Date endDate)
            throws ResourceNotFoundException, IOException, IllegalStateException, TransientException,
                   InterruptedException {
        final HarvestState harvestState = this.harvestStateDAO.get(DeletedArtifactEvent.class.getName(),
                                                                           this.resourceID);

        DateFormat df = DateUtil.getDateFormat(DateUtil.IVOA_DATE_FORMAT, DateUtil.UTC);

        SSLUtil.renewSubject(AuthenticationUtil.getCurrentSubject(), new File(CERTIFICATE_FILE_LOCATION));

        final DeletedArtifactEventSync deletedArtifactEventSync = new DeletedArtifactEventSync(tapClient);
        final DeletedArtifactEventDAO deletedArtifactEventDeletedEventDAO = new DeletedArtifactEventDAO(this.artifactDAO);

        deletedArtifactEventSync.startTime = harvestState.curLastModified;
        deletedArtifactEventSync.endTime = endDate;

        final TransactionManager transactionManager = artifactDAO.getTransactionManager();

        String start = null;
        if (harvestState.curLastModified != null) {
            start = df.format(harvestState.curLastModified);
        }
        log.info("QUERY: DeletedArtifactEvent from=" + start);
        boolean first = true;
        try (final ResourceIterator<DeletedArtifactEvent> deletedArtifactEventResourceIterator
                     = deletedArtifactEventSync.getEvents()) {

            while (deletedArtifactEventResourceIterator.hasNext()) {
                final DeletedArtifactEvent deletedArtifactEvent = deletedArtifactEventResourceIterator.next();
                if (first 
                        && deletedArtifactEvent.getID().equals(harvestState.curID)
                        && deletedArtifactEvent.getLastModified().equals(harvestState.curLastModified)) {
                    log.debug("SKIP: previously processed: " + deletedArtifactEvent.getID());
                    first = false;
                    continue; // ugh
                }
                
                try {
                    transactionManager.startTransaction();
                    // no need to acquire lock on artifact
                    log.info("PUT: DeletedArtifactEvent " + deletedArtifactEvent.getID() + " " + df.format(deletedArtifactEvent.getLastModified()));
                    deletedArtifactEventDeletedEventDAO.put(deletedArtifactEvent);
                    artifactDAO.delete(deletedArtifactEvent.getID());
                    harvestState.curLastModified = deletedArtifactEvent.getLastModified();
                    harvestState.curID = deletedArtifactEvent.getID();
                    harvestStateDAO.put(harvestState);
                    transactionManager.commitTransaction();
                } catch (Exception exception) {
                    if (transactionManager.isOpen()) {
                        log.error("Exception in transaction.  Rolling back...");
                        transactionManager.rollbackTransaction();
                        log.error("Rollback: OK");
                    }

                    throw exception;
                } finally {
                    if (transactionManager.isOpen()) {
                        log.error("BUG: transaction open in finally. Rolling back...");
                        transactionManager.rollbackTransaction();
                        log.error("Rollback: OK");
                        throw new RuntimeException("BUG: transaction open in finally");
                    }
                }
            }
        } 
    }

    /**
     * Synchronize the artifacts found by the TAP (Luskan) query.
     *
     * @param endDate                    The upper bound of the Luskan query.
     * @param storageSite                The storage site obtained from the Storage Site sync.  Optional.
     * @throws ResourceNotFoundException For any missing required configuration that is missing.
     * @throws IOException               For unreadable configuration files.
     * @throws IllegalStateException     For any invalid configuration.
     * @throws NoSuchAlgorithmException  If the MessageDigest for synchronizing Artifacts cannot be used.
     * @throws TransientException        temporary failure of TAP service: same call could work in future
     * @throws InterruptedException      thread interrupted
     */
    private void syncArtifacts(final TapClient tapClient, final Date endDate, final StorageSite storageSite)
            throws ResourceNotFoundException, IOException, IllegalStateException, NoSuchAlgorithmException,
                   InterruptedException, TransientException {
        final MessageDigest messageDigest = MessageDigest.getInstance("MD5");
        DateFormat df = DateUtil.getDateFormat(DateUtil.IVOA_DATE_FORMAT, DateUtil.UTC);

        SSLUtil.renewSubject(AuthenticationUtil.getCurrentSubject(), new File(CERTIFICATE_FILE_LOCATION));

        final HarvestState harvestState = this.harvestStateDAO.get(Artifact.class.getName(), this.resourceID);

        final ArtifactSync artifactSync = new ArtifactSync(tapClient, harvestState.curLastModified);
        artifactSync.includeClause = this.selector.getConstraint();
        artifactSync.endTime = endDate;
        
        final TransactionManager transactionManager = artifactDAO.getTransactionManager();

        DeletedArtifactEventDAO daeDAO = new DeletedArtifactEventDAO(artifactDAO);

        String start = null;
        if (harvestState.curLastModified != null) {
            start = df.format(harvestState.curLastModified);
        }
        log.info("QUERY: Artifact from=" + start);
        boolean first = true;
        try (final ResourceIterator<Artifact> artifactResourceIterator = artifactSync.iterator()) {
            while (artifactResourceIterator.hasNext()) {
                final Artifact artifact = artifactResourceIterator.next();

                if (first
                        && artifact.getID().equals(harvestState.curID)
                        && artifact.getLastModified().equals(harvestState.curLastModified)) {
                    log.debug("SKIP: previously processed: " + artifact.getID() + " " + artifact.getURI());
                    first = false;
                    // ugh but the skip is comprehensible: have to do this inside the loop when using
                    // try-with-resources
                    continue;
                }

                log.debug("START: Process Artifact " + artifact.getID() + " " + artifact.getURI());

                final URI computedChecksum = artifact.computeMetaChecksum(messageDigest);
                if (!artifact.getMetaChecksum().equals(computedChecksum)) {
                    throw new IllegalStateException("checksum mismatch: " + artifact.getID() + " " + artifact.getURI()
                            + " provided=" + artifact.getMetaChecksum() + " actual=" + computedChecksum);
                }

                Artifact collidingArtifact = artifactDAO.get(artifact.getURI());
                if (collidingArtifact != null && collidingArtifact.getID().equals(artifact.getID())) {
                    // same ID: not a collision
                    collidingArtifact = null;
                }

                try {
                    transactionManager.startTransaction();

                    // since Artifact.id and Artifact.uri are both unique keys, there is only ever one "current artifact"
                    // it normally has the same ID but may be the colliding artifact
                    Artifact currentArtifact = null;
                    try {
                        if (collidingArtifact == null) {
                            artifactDAO.lock(artifact);
                            currentArtifact = artifactDAO.get(artifact.getID());
                        } else {
                            artifactDAO.lock(collidingArtifact);
                            currentArtifact = artifactDAO.get(collidingArtifact.getID());
                        }
                    } catch (EntityNotFoundException ex) {
                        currentArtifact = null;
                    }

                    if (currentArtifact == null) {
                        // check if it was already deleted (sync from stale site)
                        DeletedArtifactEvent ev = daeDAO.get(artifact.getID());
                        if (ev != null) {
                            log.info("STALE Artifact: skip " 
                                    + artifact.getID() + "|" + artifact.getURI() + "|" + df.format(artifact.getLastModified()));
                            transactionManager.rollbackTransaction();
                            continue;
                        }
                    }

                    if (collidingArtifact != null && currentArtifact != null) {
                        // resolve collision using Artifact.contentLastModified
                        if (currentArtifact.getContentLastModified().before(artifact.getContentLastModified())) {
                            log.info("Artifact.uri COLLISION: replace " 
                                    + currentArtifact.getID() + "|" + currentArtifact.getURI() + "|" + df.format(currentArtifact.getContentLastModified())
                                    + " with " + artifact.getID() + "|" + artifact.getURI() + "|" + df.format(artifact.getContentLastModified()));
                            daeDAO.put(new DeletedArtifactEvent(currentArtifact.getID()));
                            artifactDAO.delete(currentArtifact.getID());
                        } else {
                            log.info("Artifact.uri COLLISION: skip " 
                                    + artifact.getID() + " " + artifact.getURI() + " " + df.format(artifact.getLastModified()));
                            transactionManager.rollbackTransaction();
                            continue;
                        }
                    }

                    log.info("PUT: artifact " + artifact.getID() + " " + artifact.getURI() + " " + df.format(artifact.getLastModified()));
                    if (storageSite != null && currentArtifact != null && artifact.getMetaChecksum().equals(currentArtifact.getMetaChecksum())) {
                        // only adding a SiteLocation
                        artifactDAO.addSiteLocation(currentArtifact, new SiteLocation(storageSite.getID()));
                    } else {
                        // new artifact || updated metadata
                        if (storageSite != null) {
                            // trackSiteLocations: merge SiteLocation(s)
                            artifact.siteLocations.add(new SiteLocation(storageSite.getID()));
                            if (currentArtifact != null) {
                                artifact.siteLocations.addAll(currentArtifact.siteLocations);
                            }
                        } else {
                            // storage site: keep StorageLocation
                            if (currentArtifact != null) {
                                artifact.storageLocation = currentArtifact.storageLocation;
                            }
                        }
                        artifactDAO.put(artifact);
                    }

                    harvestState.curLastModified = artifact.getLastModified();
                    harvestState.curID = artifact.getID();
                    harvestStateDAO.put(harvestState);
                    transactionManager.commitTransaction();
                    log.debug("END: Process Artifact " + artifact.getID() + " " + artifact.getURI());
                } catch (Exception exception) {
                    if (transactionManager.isOpen()) {
                        log.error("Exception in transaction.  Rolling back...");
                        transactionManager.rollbackTransaction();
                        log.error("Rollback: OK");
                    }

                    throw exception;
                } finally {
                    if (transactionManager.isOpen()) {
                        log.error("BUG: transaction open in finally. Rolling back...");
                        transactionManager.rollbackTransaction();
                        log.error("Rollback: OK");
                        throw new RuntimeException("BUG: transaction open in finally");
                    }
                }
            }
        }
    }

    private void logRetry(int retries, int timeout, String reason, String message) {
        log.error(String.format("retry[%s] timeout %ss - reason: %s, %s", retries, timeout, reason, message));
    }

    private void logExit(String reason, String message) {
        log.error(String.format("Exiting, reason - %s, %s", reason, message));
    }
}
