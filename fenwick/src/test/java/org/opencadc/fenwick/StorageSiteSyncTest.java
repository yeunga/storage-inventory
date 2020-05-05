
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
 *
 ************************************************************************
 */

package org.opencadc.fenwick;

import ca.nrc.cadc.io.ResourceIterator;
import ca.nrc.cadc.util.Log4jInit;

import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;

import org.apache.log4j.Level;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.opencadc.inventory.InventoryUtil;
import org.opencadc.inventory.StorageSite;


public class StorageSiteSyncTest {
    static {
        Log4jInit.setLevel("org.opencadc.fenwick", Level.DEBUG);
    }

    @Test
    @Ignore("Would require a StorageSiteDAO to be mocked.")
    public void doit() throws Exception {
        final List<StorageSite> storageSiteList = new ArrayList<>();
        final Calendar cal = Calendar.getInstance();
        cal.set(1977, Calendar.NOVEMBER, 25, 3, 12, 0);
        cal.set(Calendar.MILLISECOND, 0);
        final MessageDigest messageDigest = MessageDigest.getInstance("MD5");
        final StorageSite testStorageSite = new StorageSite(URI.create("test:org.opencadc/SITE1"), "Site One");
        InventoryUtil.assignLastModified(testStorageSite, cal.getTime());
        InventoryUtil.assignMetaChecksum(testStorageSite, testStorageSite.computeMetaChecksum(messageDigest));

        storageSiteList.add(testStorageSite);

        final StorageSiteSync testSubject = new StorageSiteSync(null, null) {
            /**
             * Override this method in tests to avoid recreating a TapClient.
             *
             * @return Iterator over Storage Sites, or empty Iterator.  Never null.
             */
            @Override
            ResourceIterator<StorageSite> queryStorageSites() {
                final Iterator<StorageSite> storageSiteIterator = storageSiteList.iterator();
                return new ResourceIterator<StorageSite>() {
                    @Override
                    public void close() throws IOException {

                    }

                    @Override
                    public boolean hasNext() {
                        return storageSiteIterator.hasNext();
                    }

                    @Override
                    public StorageSite next() {
                        return storageSiteIterator.next();
                    }
                };
            }
        };

        final StorageSite storageSiteResult = testSubject.doit();

        Assert.assertEquals("Wrong site list.", storageSiteList.get(0), storageSiteResult);
    }

    @Test
    public void doitException() throws Exception {
        final List<StorageSite> storageSiteList = new ArrayList<>();
        storageSiteList.add(new StorageSite(URI.create("test:org.opencadc/SITE1"), "Site One"));
        storageSiteList.add(new StorageSite(URI.create("test:org.opencadc/SITE2"), "Site Two"));

        StorageSiteSync testSubject = new StorageSiteSync(null, null) {
            /**
             * Override this method in tests to avoid recreating a TapClient.
             *
             * @return Iterator over Storage Sites, or empty Iterator.  Never null.
             */
            @Override
            ResourceIterator<StorageSite> queryStorageSites() {
                final Iterator<StorageSite> storageSiteIterator = storageSiteList.iterator();
                return new ResourceIterator<StorageSite>() {
                    @Override
                    public void close() throws IOException {

                    }

                    @Override
                    public boolean hasNext() {
                        return storageSiteIterator.hasNext();
                    }

                    @Override
                    public StorageSite next() {
                        return storageSiteIterator.next();
                    }
                };
            }
        };

        try {
            testSubject.doit();
            Assert.fail("Should throw IllegalStateException");
        } catch (IllegalStateException e) {
            // Good.
            Assert.assertEquals("Wrong message.", "More than one Storage Site found.", e.getMessage());
        }

        // Second kind of exception

        storageSiteList.clear();
        testSubject = new StorageSiteSync(null, null) {
            /**
             * Override this method in tests to avoid recreating a TapClient.
             *
             * @return Iterator over Storage Sites, or empty Iterator.  Never null.
             */
            @Override
            ResourceIterator<StorageSite> queryStorageSites() {
                final Iterator<StorageSite> storageSiteIterator = storageSiteList.iterator();
                return new ResourceIterator<StorageSite>() {
                    @Override
                    public void close() throws IOException {

                    }

                    @Override
                    public boolean hasNext() {
                        return storageSiteIterator.hasNext();
                    }

                    @Override
                    public StorageSite next() {
                        return storageSiteIterator.next();
                    }
                };
            }
        };

        try {
            testSubject.doit();
            Assert.fail("Should throw IllegalStateException");
        } catch (IllegalStateException e) {
            // Good.
            Assert.assertEquals("Wrong message.", "No storage sites available to sync.", e.getMessage());
        }
    }

    @Test
    @Ignore("Would require a StorageSiteDAO to be mocked.")
    public void doitInvalidChecksum() throws Exception {
        final List<StorageSite> storageSiteList = new ArrayList<>();
        final Calendar cal = Calendar.getInstance();
        cal.set(1977, Calendar.NOVEMBER, 25, 3, 12, 0);
        cal.set(Calendar.MILLISECOND, 0);
        final StorageSite testStorageSite = new StorageSite(URI.create("test:org.opencadc/MAINSITE"), "Main Site");
        InventoryUtil.assignLastModified(testStorageSite, cal.getTime());
        InventoryUtil.assignMetaChecksum(testStorageSite, URI.create("md5:incorrect"));

        storageSiteList.add(testStorageSite);

        final StorageSiteSync testSubject = new StorageSiteSync(null, null) {
            /**
             * Override this method in tests to avoid recreating a TapClient.
             *
             * @return Iterator over Storage Sites, or empty Iterator.  Never null.
             */
            @Override
            ResourceIterator<StorageSite> queryStorageSites() {
                final Iterator<StorageSite> storageSiteIterator = storageSiteList.iterator();
                return new ResourceIterator<StorageSite>() {
                    @Override
                    public void close() throws IOException {

                    }

                    @Override
                    public boolean hasNext() {
                        return storageSiteIterator.hasNext();
                    }

                    @Override
                    public StorageSite next() {
                        return storageSiteIterator.next();
                    }
                };
            }
        };

        try {
            testSubject.doit();
        } catch (IllegalStateException e) {
            // Good.
            Assert.assertTrue("Wrong message.", e.getMessage().contains(
                    "Discovered Storage Site checksum (md5:incorrect) does not match computed value"));
        }
    }
}
