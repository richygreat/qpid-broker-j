/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.server.security;

import org.apache.qpid.server.model.Content;
import org.apache.qpid.server.model.DerivedAttribute;
import org.apache.qpid.server.model.KeyStore;
import org.apache.qpid.server.model.ManagedAttribute;
import org.apache.qpid.server.model.ManagedObject;
import org.apache.qpid.server.model.ManagedOperation;
import org.apache.qpid.server.model.Param;

@ManagedObject( category = false, type = "AutoGeneratedSelfSigned" )
public interface AutoGeneratedSelfSignedKeyStore<X extends AutoGeneratedSelfSignedKeyStore<X>> extends KeyStore<X>
{
    String ENCODED_CERTIFICATE = "encodedCertificate";
    String ENCODED_PRIVATE_KEY = "encodedPrivateKey";

    @ManagedAttribute(defaultValue="RSA", immutable = true)
    String getKeyAlgorithm();

    @ManagedAttribute(defaultValue="SHA256WithRSA", immutable = true)
    String getSignatureAlgorithm();

    @ManagedAttribute(defaultValue="2048", immutable = true)
    int getKeyLength();

    @ManagedAttribute(defaultValue="12", immutable = true)
    int getDurationInMonths();

    @DerivedAttribute(persist = true)
    String getEncodedCertificate();
    @DerivedAttribute(persist = true, secure = true)
    String getEncodedPrivateKey();

    @ManagedOperation(description = "create a new private key and a new certificate",
            changesConfiguredObjectState = true)
    void regenerateCertificate();

    @ManagedOperation(nonModifying = true,
            description = "Extract a Java KeyStore containing the certificate for the autogenerated key."
                          + "  The KeyStore can be used as a trust store by JMS client connecting to the broker",
            changesConfiguredObjectState = false)
    Content getClientTrustStore(@Param(name="password", defaultValue = "", description = "The password to use to secure the keystore (by default the empty string is used).") String password);

    @ManagedOperation(nonModifying = true,
            description = "Extract a PEM file containing the certificate for the autogenerated key.",
            changesConfiguredObjectState = false)
    Content getCertificate();


}
