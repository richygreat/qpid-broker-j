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
package org.apache.qpid.tests.protocol.v1_0.extensions.anonymousterminus;


import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assume.assumeThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;

import org.apache.qpid.server.bytebuffer.QpidByteBuffer;
import org.apache.qpid.server.protocol.v1_0.SequenceNumber;
import org.apache.qpid.server.protocol.v1_0.type.Binary;
import org.apache.qpid.server.protocol.v1_0.type.DeliveryState;
import org.apache.qpid.server.protocol.v1_0.type.Symbol;
import org.apache.qpid.server.protocol.v1_0.type.UnsignedInteger;
import org.apache.qpid.server.protocol.v1_0.type.messaging.Accepted;
import org.apache.qpid.server.protocol.v1_0.type.messaging.Properties;
import org.apache.qpid.server.protocol.v1_0.type.messaging.Rejected;
import org.apache.qpid.server.protocol.v1_0.type.messaging.Target;
import org.apache.qpid.server.protocol.v1_0.type.transaction.Coordinator;
import org.apache.qpid.server.protocol.v1_0.type.transaction.Discharge;
import org.apache.qpid.server.protocol.v1_0.type.transaction.TransactionError;
import org.apache.qpid.server.protocol.v1_0.type.transaction.TransactionalState;
import org.apache.qpid.server.protocol.v1_0.type.transport.AmqpError;
import org.apache.qpid.server.protocol.v1_0.type.transport.Attach;
import org.apache.qpid.server.protocol.v1_0.type.transport.Begin;
import org.apache.qpid.server.protocol.v1_0.type.transport.Detach;
import org.apache.qpid.server.protocol.v1_0.type.transport.Disposition;
import org.apache.qpid.server.protocol.v1_0.type.transport.Error;
import org.apache.qpid.server.protocol.v1_0.type.transport.Flow;
import org.apache.qpid.server.protocol.v1_0.type.transport.Open;
import org.apache.qpid.server.protocol.v1_0.type.transport.Role;
import org.apache.qpid.tests.protocol.Response;
import org.apache.qpid.tests.protocol.SpecificationTest;
import org.apache.qpid.tests.protocol.v1_0.FrameTransport;
import org.apache.qpid.tests.protocol.v1_0.Interaction;
import org.apache.qpid.tests.protocol.v1_0.InteractionTransactionalState;
import org.apache.qpid.tests.protocol.v1_0.MessageEncoder;
import org.apache.qpid.tests.protocol.v1_0.Utils;
import org.apache.qpid.tests.utils.BrokerAdmin;
import org.apache.qpid.tests.utils.BrokerAdminUsingTestBase;

public class AnonymousTerminusTest extends BrokerAdminUsingTestBase
{
    private static final Symbol ANONYMOUS_RELAY = Symbol.valueOf("ANONYMOUS-RELAY");
    private static final Symbol DELIVERY_TAG = Symbol.valueOf("delivery-tag");
    private static final String TEST_MESSAGE_CONTENT = "test";
    private InetSocketAddress _brokerAddress;
    private Binary _deliveryTag;

    @Before
    public void setUp()
    {
        final BrokerAdmin brokerAdmin = getBrokerAdmin();
        brokerAdmin.createQueue(BrokerAdmin.TEST_QUEUE_NAME);
        _brokerAddress = brokerAdmin.getBrokerAddress(BrokerAdmin.PortType.ANONYMOUS_AMQP);
        _deliveryTag = new Binary("testTag".getBytes(StandardCharsets.UTF_8));
    }

    @SpecificationTest(section = "Using the Anonymous Terminus for Message Routing. 2.2. Sending A Message",
            description = "Messages sent over links into a routing node will be"
                          + " forwarded to the node referenced in the to field of properties of the message"
                          + " just as if a direct link has been established to that node.")
    @Test
    public void transferPreSettledToKnownDestination() throws Exception
    {
        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            final Interaction interaction = openInteractionWithAnonymousRelayCapability(transport);

            interaction.begin()
                       .consumeResponse(Begin.class)

                       .attachRole(Role.SENDER)
                       .attach().consumeResponse(Attach.class)
                       .consumeResponse(Flow.class)

                       .transferPayload(generateMessagePayloadToDestination(BrokerAdmin.TEST_QUEUE_NAME))
                       .transferSettled(Boolean.TRUE)
                       .transferDeliveryTag(_deliveryTag)
                       .transfer()
                       .sync();

            Object receivedMessage = Utils.receiveMessage(_brokerAddress, BrokerAdmin.TEST_QUEUE_NAME);
            assertThat(receivedMessage, is(equalTo(TEST_MESSAGE_CONTENT)));
        }
    }

    @SpecificationTest(section = "Using the Anonymous Terminus for Message Routing. 2.2.2 Routing Errors",
            description = "It is possible that a message sent to a routing node has an address in the to field"
                          + " of properties which, if used in the address field of target of an attach,"
                          + " would result in an unsuccessful link establishment (for example,"
                          + " if the address cannot be resolved to a node). In this case the routing node"
                          + " MUST communicate the error back to the sender of the message."
                          + " [...] the message has already been settled by the sender,"
                          + " then the routing node MUST detach the link with an error."
                          + " [...] the info field of error MUST contain an entry with symbolic key delivery-tag"
                          + " and binary value of the delivery-tag of the message which caused the failure.")
    @Test
    public void transferPreSettledToUnknownDestination() throws Exception
    {
        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            final Interaction interaction = openInteractionWithAnonymousRelayCapability(transport);

            interaction.begin()
                       .consumeResponse(Begin.class)

                       .attachRole(Role.SENDER)
                       .attach().consumeResponse(Attach.class)
                       .consumeResponse(Flow.class)

                       .transferPayload(generateMessagePayloadToDestination("Unknown"))
                       .transferSettled(Boolean.TRUE)
                       .transferDeliveryTag(_deliveryTag)
                       .transfer();

            Detach detach = interaction.consumeResponse().getLatestResponse(Detach.class);
            Error error = detach.getError();
            assertThat(error, is(notNullValue()));
            assertThat(error.getCondition(), is(equalTo(AmqpError.NOT_FOUND)));
            assertThat(error.getInfo(), is(notNullValue()));
            assertThat(error.getInfo().get(DELIVERY_TAG), is(equalTo(_deliveryTag)));
        }
    }

    @SpecificationTest(section = "Using the Anonymous Terminus for Message Routing. 2.2.2 Routing Errors",
            description = "It is possible that a message sent to a routing node has an address in the to field"
                          + " of properties which, if used in the address field of target of an attach,"
                          + " would result in an unsuccessful link establishment (for example,"
                          + " if the address cannot be resolved to a node). In this case the routing node"
                          + " MUST communicate the error back to the sender of the message."
                          + " If the source of the link supports the rejected outcome,"
                          + " and the message has not already been settled by the sender, then the routing node"
                          + " MUST reject the message."
                          + " [...] the info field of error MUST contain an entry with symbolic key delivery-tag"
                          + " and binary value of the delivery-tag of the message which caused the failure.")
    @Test
    public void transferUnsettledToUnknownDestinationWhenRejectedOutcomeSupportedBySource() throws Exception
    {
        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            final Interaction interaction = openInteractionWithAnonymousRelayCapability(transport);

            interaction.begin()
                       .consumeResponse(Begin.class)

                       .attachRole(Role.SENDER)
                       .attachSourceOutcomes(Accepted.ACCEPTED_SYMBOL, Rejected.REJECTED_SYMBOL)
                       .attach().consumeResponse(Attach.class)
                       .consumeResponse(Flow.class)

                       .transferPayload(generateMessagePayloadToDestination("Unknown"))
                       .transferDeliveryTag(_deliveryTag)
                       .transfer()
                       .consumeResponse();

            Disposition disposition = interaction.getLatestResponse(Disposition.class);

            assertThat(disposition.getSettled(), is(true));

            DeliveryState dispositionState = disposition.getState();
            assertThat(dispositionState, is(instanceOf(Rejected.class)));

            Rejected rejected = (Rejected)dispositionState;
            Error error = rejected.getError();
            assertThat(error, is(notNullValue()));
            assertThat(error.getCondition(), is(equalTo(AmqpError.NOT_FOUND)));
            assertThat(error.getInfo(), is(notNullValue()));
            assertThat(error.getInfo().get(DELIVERY_TAG), is(equalTo(_deliveryTag)));
        }
    }

    @SpecificationTest(section = "Using the Anonymous Terminus for Message Routing. 2.2.2 Routing Errors",
            description = "It is possible that a message sent to a routing node has an address in the to field"
                          + " of properties which, if used in the address field of target of an attach,"
                          + " would result in an unsuccessful link establishment (for example,"
                          + " if the address cannot be resolved to a node). In this case the routing node"
                          + " MUST communicate the error back to the sender of the message."
                          + " [...]"
                          + " If the source of the link does not support the rejected outcome,"
                          + " [...] then the routing node MUST detach the link with an error."
                          + " [...] the info field of error MUST contain an entry with symbolic key delivery-tag"
                          + " and binary value of the delivery-tag of the message which caused the failure.")
    @Test
    public void transferUnsettledToUnknownDestinationWhenRejectedOutcomeNotSupportedBySource() throws Exception
    {
        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            final Interaction interaction = openInteractionWithAnonymousRelayCapability(transport);

            interaction.begin()
                       .consumeResponse(Begin.class)

                       .attachRole(Role.SENDER)
                       .attachSourceOutcomes(Accepted.ACCEPTED_SYMBOL)
                       .attach().consumeResponse(Attach.class)
                       .consumeResponse(Flow.class)

                       .transferPayload(generateMessagePayloadToDestination("Unknown"))
                       .transferDeliveryTag(_deliveryTag)
                       .transfer();

            Detach detach = interaction.consumeResponse().getLatestResponse(Detach.class);
            Error error = detach.getError();
            assertThat(error, is(notNullValue()));
            assertThat(error.getCondition(), is(equalTo(AmqpError.NOT_FOUND)));
            assertThat(error.getInfo(), is(notNullValue()));
            assertThat(error.getInfo().get(DELIVERY_TAG), is(equalTo(_deliveryTag)));
        }
    }

    @SpecificationTest(section = "Using the Anonymous Terminus for Message Routing. 2.2. Sending A Message",
            description = "Messages sent over links into a routing node will be"
                          + " forwarded to the node referenced in the to field of properties of the message"
                          + " just as if a direct link has been established to that node.")
    @Test
    public void transferPreSettledInTransactionToKnownDestination() throws Exception
    {
        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            final Interaction interaction = openInteractionWithAnonymousRelayCapability(transport);
            final UnsignedInteger linkHandle = UnsignedInteger.ONE;
            final InteractionTransactionalState txnState = interaction.createTransactionalState(UnsignedInteger.ZERO);
            interaction.begin()
                       .consumeResponse(Begin.class)

                       .txnAttachCoordinatorLink(txnState)
                       .txnDeclare(txnState)

                       .attachRole(Role.SENDER)
                       .attachHandle(linkHandle)
                       .attach().consumeResponse(Attach.class)
                       .consumeResponse(Flow.class)

                       .transferHandle(linkHandle)
                       .transferPayload(generateMessagePayloadToDestination(BrokerAdmin.TEST_QUEUE_NAME))
                       .transferDeliveryTag(_deliveryTag)
                       .transferTransactionalState(txnState.getCurrentTransactionId())
                       .transferSettled(Boolean.TRUE)
                       .transfer()

                       .txnDischarge(txnState, false);

            Object receivedMessage = Utils.receiveMessage(_brokerAddress, BrokerAdmin.TEST_QUEUE_NAME);
            assertThat(receivedMessage, is(equalTo(TEST_MESSAGE_CONTENT)));
        }
    }

    @Test
    public void transferUnsettledInTransactionToKnownDestination() throws Exception
    {
        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            final Interaction interaction = openInteractionWithAnonymousRelayCapability(transport);
            final UnsignedInteger linkHandle = UnsignedInteger.ONE;
            final InteractionTransactionalState txnState = interaction.createTransactionalState(UnsignedInteger.ZERO);
            interaction.begin()
                       .consumeResponse(Begin.class)

                       .txnAttachCoordinatorLink(txnState)
                       .txnDeclare(txnState)

                       .attachRole(Role.SENDER)
                       .attachHandle(linkHandle)
                       .attach().consumeResponse(Attach.class)
                       .consumeResponse(Flow.class)

                       .transferHandle(linkHandle)
                       .transferPayload(generateMessagePayloadToDestination(BrokerAdmin.TEST_QUEUE_NAME))
                       .transferDeliveryTag(_deliveryTag)
                       .transferTransactionalState(txnState.getCurrentTransactionId())
                       .transferSettled(Boolean.FALSE)
                       .transfer();

            Disposition disposition = interaction.consumeResponse().getLatestResponse(Disposition.class);

            assertThat(disposition.getSettled(), is(true));

            DeliveryState dispositionState = disposition.getState();
            assertThat(dispositionState, is(instanceOf(TransactionalState.class)));

            final TransactionalState receivedTxnState = (TransactionalState) dispositionState;
            assertThat(receivedTxnState.getOutcome(), is(instanceOf(Accepted.class)));

            interaction.txnDischarge(txnState, false);

            Object receivedMessage = Utils.receiveMessage(_brokerAddress, BrokerAdmin.TEST_QUEUE_NAME);
            assertThat(receivedMessage, is(equalTo(TEST_MESSAGE_CONTENT)));
        }
    }

    @Test
    public void transferUnsettledInTransactionToUnknownDestinationWhenRejectedOutcomeSupportedBySource() throws Exception
    {
        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            final Interaction interaction = openInteractionWithAnonymousRelayCapability(transport);
            final UnsignedInteger linkHandle = UnsignedInteger.ONE;
            final InteractionTransactionalState txnState = interaction.createTransactionalState(UnsignedInteger.ZERO);
            interaction.begin()
                       .consumeResponse(Begin.class)

                       .txnAttachCoordinatorLink(txnState)
                       .txnDeclare(txnState)

                       .attachRole(Role.SENDER)
                       .attachSourceOutcomes(Accepted.ACCEPTED_SYMBOL, Rejected.REJECTED_SYMBOL)
                       .attachHandle(linkHandle)
                       .attach().consumeResponse(Attach.class)
                       .consumeResponse(Flow.class)

                       .transferHandle(linkHandle)
                       .transferPayload(generateMessagePayloadToDestination("Unknown"))
                       .transferDeliveryTag(_deliveryTag)
                       .transferTransactionalState(txnState.getCurrentTransactionId())
                       .transferSettled(Boolean.FALSE)
                       .transfer();

            Disposition disposition = interaction.consumeResponse().getLatestResponse(Disposition.class);

            assertThat(disposition.getSettled(), is(true));

            DeliveryState dispositionState = disposition.getState();
            assertThat(dispositionState, is(instanceOf(TransactionalState.class)));

            final TransactionalState receivedTxnState = (TransactionalState) dispositionState;
            assertThat(receivedTxnState.getOutcome(), is(instanceOf(Rejected.class)));

            final Error rejectedError = ((Rejected) receivedTxnState.getOutcome()).getError();
            assertThat(rejectedError.getCondition(), is(equalTo(AmqpError.NOT_FOUND)));
            assertThat(rejectedError.getInfo(), is(notNullValue()));
            assertThat(rejectedError.getInfo().get(DELIVERY_TAG), is(equalTo(_deliveryTag)));

            interaction.txnDischarge(txnState, false);
        }
    }

    @Test
    public void transferUnsettledInTransactionToUnknownDestinationWhenRejectedOutcomeNotSupportedBySource() throws Exception
    {
        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            final Interaction interaction = openInteractionWithAnonymousRelayCapability(transport);
            final UnsignedInteger linkHandle = UnsignedInteger.ONE;
            final InteractionTransactionalState txnState = interaction.createTransactionalState(UnsignedInteger.ZERO);
            interaction.begin()
                       .consumeResponse(Begin.class)

                       .txnAttachCoordinatorLink(txnState)
                       .txnDeclare(txnState)

                       .attachRole(Role.SENDER)
                       .attachSourceOutcomes(Accepted.ACCEPTED_SYMBOL)
                       .attachHandle(linkHandle)
                       .attach().consumeResponse(Attach.class)
                       .consumeResponse(Flow.class)

                       .transferHandle(linkHandle)
                       .transferPayload(generateMessagePayloadToDestination("Unknown"))
                       .transferDeliveryId(UnsignedInteger.valueOf(1))
                       .transferDeliveryTag(_deliveryTag)
                       .transferTransactionalState(txnState.getCurrentTransactionId())
                       .transferSettled(Boolean.FALSE)
                       .transfer();

            Detach senderLinkDetach = interaction.consumeResponse().getLatestResponse(Detach.class);
            Error senderLinkDetachError = senderLinkDetach.getError();
            assertThat(senderLinkDetachError, is(notNullValue()));
            assertThat(senderLinkDetachError.getCondition(), is(equalTo(AmqpError.NOT_FOUND)));
            assertThat(senderLinkDetachError.getInfo(), is(notNullValue()));
            assertThat(senderLinkDetachError.getInfo().get(DELIVERY_TAG), is(equalTo(_deliveryTag)));

            final Discharge discharge = new Discharge();
            discharge.setTxnId(txnState.getCurrentTransactionId());
            discharge.setFail(false);

            interaction.transferHandle(txnState.getHandle())
                       .transferSettled(Boolean.FALSE)
                       .transferDeliveryId(UnsignedInteger.valueOf(2))
                       .transferDeliveryTag(new Binary(("transaction-" + 2).getBytes(StandardCharsets.UTF_8)))
                       .transferPayloadData(discharge).transfer();

            Disposition dischargeTransactionDisposition =
                    getDispositionForDeliveryId(interaction, UnsignedInteger.valueOf(2));

            assertThat(dischargeTransactionDisposition.getSettled(), is(equalTo(true)));
            assertThat(dischargeTransactionDisposition.getState(), is(instanceOf(Rejected.class)));

            Rejected rejected = (Rejected) dischargeTransactionDisposition.getState();
            Error error = rejected.getError();

            assertThat(error, is(notNullValue()));
            assertThat(error.getCondition(), is(equalTo(TransactionError.TRANSACTION_ROLLBACK)));
        }
    }

    @SpecificationTest(section = "Using the Anonymous Terminus for Message Routing. 2.2.2 Routing Errors",
            description = "It is possible that a message sent to a routing node has an address in the to field"
                          + " of properties which, if used in the address field of target of an attach,"
                          + " would result in an unsuccessful link establishment (for example,"
                          + " if the address cannot be resolved to a node). In this case the routing node"
                          + " MUST communicate the error back to the sender of the message."
                          + " [...]"
                          + " <Not in spec yet>"
                          + " AMQP-140"
                          + " If a message cannot be routed to the destination implied in the \"to:\" field,"
                          + " and the source does not allow for the rejected outcome"
                          + " [...] when messages are being sent within a transaction and have been sent pre-settled."
                          + " In this case the behaviour defined for transactions (of essentially marking"
                          + " the transaction as rollback only) should take precedence. "
                            + ""
                          + " AMQP spec 4.3 Discharging a Transaction"
                          + " If the coordinator is unable to complete the discharge, the coordinator MUST convey"
                          + " the error to the controller as a transaction-error. If the source for the link to"
                          + " the coordinator supports the rejected outcome, then the message MUST be rejected"
                          + " with this outcome carrying the transaction-error.")
    @Test
    public void transferPreSettledInTransactionToUnknownDestinationWhenRejectOutcomeSupportedByTxController()
            throws Exception
    {
        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            final UnsignedInteger linkHandle = UnsignedInteger.ONE;
            final Interaction interaction =
                    openInteractionWithAnonymousRelayCapability(transport);

            final InteractionTransactionalState txnState = interaction.createTransactionalState(UnsignedInteger.ZERO);
            interaction.begin()
                       .consumeResponse(Begin.class)

                       // attaching coordinator link with supported outcomes Accepted and Rejected
                       .txnAttachCoordinatorLink(txnState)
                       .txnDeclare(txnState)

                       .attachRole(Role.SENDER)
                       .attachHandle(linkHandle)
                       .attachSourceOutcomes(Accepted.ACCEPTED_SYMBOL, Rejected.REJECTED_SYMBOL)
                       .attach().consumeResponse(Attach.class)
                       .consumeResponse(Flow.class)

                       .transferHandle(linkHandle)
                       .transferPayload(generateMessagePayloadToDestination("Unknown"))
                       .transferDeliveryTag(_deliveryTag)
                       .transferTransactionalState(txnState.getCurrentTransactionId())
                       .transferSettled(Boolean.TRUE)
                       .transferDeliveryId(UnsignedInteger.valueOf(1))
                       .transfer();

            final Discharge discharge = new Discharge();
            discharge.setTxnId(txnState.getCurrentTransactionId());
            discharge.setFail(false);
            interaction.transferHandle(txnState.getHandle())
                       .transferDeliveryId(UnsignedInteger.valueOf(2))
                       .transferSettled(Boolean.FALSE)
                       .transferDeliveryTag(new Binary(("transaction-" + 2).getBytes(StandardCharsets.UTF_8)))
                       .transferPayloadData(discharge).transfer();

            Disposition dischargeTransactionDisposition =
                    getDispositionForDeliveryId(interaction, UnsignedInteger.valueOf(2));

            assertThat(dischargeTransactionDisposition.getSettled(), is(equalTo(true)));
            assertThat(dischargeTransactionDisposition.getState(), is(instanceOf(Rejected.class)));

            Rejected rejected = (Rejected) dischargeTransactionDisposition.getState();
            Error error = rejected.getError();

            assertThat(error, is(notNullValue()));
            assertThat(error.getCondition(), is(equalTo(TransactionError.TRANSACTION_ROLLBACK)));
        }
    }

    @SpecificationTest(section = "Using the Anonymous Terminus for Message Routing. 2.2.2 Routing Errors",
            description = "It is possible that a message sent to a routing node has an address in the to field"
                          + " of properties which, if used in the address field of target of an attach,"
                          + " would result in an unsuccessful link establishment (for example,"
                          + " if the address cannot be resolved to a node). In this case the routing node"
                          + " MUST communicate the error back to the sender of the message."
                          + " [...]"
                          + " <Not in spec yet>"
                          + " AMQP-140"
                          + " If a message cannot be routed to the destination implied in the \"to:\" field,"
                          + " and the source does not allow for the rejected outcome"
                          + " [...] when messages are being sent within a transaction and have been sent pre-settled."
                          + " In this case the behaviour defined for transactions (of essentially marking"
                          + " the transaction as rollback only) should take precedence. "
                          + ""
                          + " AMQP spec 4.3 Discharging a Transaction"
                          + " If the coordinator is unable to complete the discharge, the coordinator MUST convey"
                          + " the error to the controller as a transaction-error."
                          + " [...]"
                          + " If the source does not support the rejected outcome, the transactional resource MUST"
                          + " detach the link to the coordinator, with the detach performative carrying"
                          + " the transaction-error")
    @Test
    public void transferPreSettledInTransactionToUnknownDestinationWhenRejectOutcomeNotSupportedByTxController()
            throws Exception
    {
        try (FrameTransport transport = new FrameTransport(_brokerAddress).connect())
        {
            final UnsignedInteger linkHandle = UnsignedInteger.ONE;
            final Interaction interaction =
                    openInteractionWithAnonymousRelayCapability(transport);

            final InteractionTransactionalState txnState = interaction.createTransactionalState(UnsignedInteger.ZERO);

            interaction.begin()
                       .consumeResponse(Begin.class)

                       .attachRole(Role.SENDER)
                       .attachName("testTransactionCoordinator-" + txnState.getHandle())
                       .attachHandle(txnState.getHandle())
                       .attachInitialDeliveryCount(UnsignedInteger.ZERO)
                       .attachTarget(new Coordinator())
                       .attachSourceOutcomes(Accepted.ACCEPTED_SYMBOL)
                       .attach().consumeResponse(Attach.class)
                       .consumeResponse(Flow.class)
                       .txnDeclare(txnState)

                       .attachRole(Role.SENDER)
                       .attachHandle(linkHandle)
                       .attachTarget(new Target())
                       .attachName("link-" + linkHandle)
                       .attachSourceOutcomes(Accepted.ACCEPTED_SYMBOL, Rejected.REJECTED_SYMBOL)
                       .attach().consumeResponse(Attach.class)
                       .consumeResponse(Flow.class)

                       .transferHandle(linkHandle)
                       .transferPayload(generateMessagePayloadToDestination("Unknown"))
                       .transferDeliveryTag(_deliveryTag)
                       .transferTransactionalState(txnState.getCurrentTransactionId())
                       .transferSettled(Boolean.TRUE)
                       .transfer();

            final Discharge discharge = new Discharge();
            discharge.setTxnId(txnState.getCurrentTransactionId());
            discharge.setFail(false);

            interaction.transferHandle(txnState.getHandle())
                       .transferSettled(Boolean.FALSE)
                       .transferDeliveryId(UnsignedInteger.valueOf(4))
                       .transferDeliveryTag(new Binary(("transaction-" + 4).getBytes(StandardCharsets.UTF_8)))
                       .transferPayloadData(discharge).transfer();

            Detach transactionCoordinatorDetach = interaction.consumeResponse().getLatestResponse(Detach.class);
            Error transactionCoordinatorDetachError = transactionCoordinatorDetach.getError();
            assertThat(transactionCoordinatorDetachError, is(notNullValue()));
            assertThat(transactionCoordinatorDetachError.getCondition(), is(equalTo(TransactionError.TRANSACTION_ROLLBACK)));
        }
    }

    private Disposition getDispositionForDeliveryId(final Interaction interaction,
                                                    final UnsignedInteger deliveryId) throws Exception
    {
        Disposition dischargeTransactionDisposition = null;

        SequenceNumber id = new SequenceNumber(deliveryId.intValue());
        do
        {
            Response<?> response = interaction.consumeResponse(Disposition.class, Flow.class).getLatestResponse();
            if (response.getBody() instanceof Disposition)
            {
                Disposition disposition = (Disposition) response.getBody();
                UnsignedInteger first = disposition.getFirst();
                UnsignedInteger last = disposition.getLast() == null ? disposition.getFirst() : disposition.getLast();
                if (new SequenceNumber(first.intValue()).compareTo(id) >= 0 && new SequenceNumber(last.intValue()).compareTo(id) <=0)
                {
                    dischargeTransactionDisposition = disposition;
                }
            }
        } while (dischargeTransactionDisposition == null);
        return dischargeTransactionDisposition;
    }

    private Interaction openInteractionWithAnonymousRelayCapability(final FrameTransport transport) throws Exception
    {
        final Interaction interaction = transport.newInteraction();
        interaction.negotiateProtocol().consumeResponse()
                   .openDesiredCapabilities(ANONYMOUS_RELAY)
                   .open().consumeResponse(Open.class);

        Open open = interaction.getLatestResponse(Open.class);
        assumeThat(Arrays.asList(open.getOfferedCapabilities()), hasItem(ANONYMOUS_RELAY));
        return interaction;
    }

    private QpidByteBuffer generateMessagePayloadToDestination(final String destinationName)
    {
        MessageEncoder messageEncoder = new MessageEncoder();
        final Properties properties = new Properties();
        properties.setTo(destinationName);
        messageEncoder.setProperties(properties);
        messageEncoder.addData(TEST_MESSAGE_CONTENT);
        return messageEncoder.getPayload();
    }
}
