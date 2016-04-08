/*
 * Copyright 2014-2016 Groupon, Inc
 * Copyright 2014-2016 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.plugin.adyen.core;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.UUID;

import javax.annotation.Nullable;

import org.joda.time.DateTime;
import org.killbill.adyen.notification.NotificationRequestItem;
import org.killbill.billing.ErrorCode;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.account.api.AccountApiException;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.payment.api.Payment;
import org.killbill.billing.payment.api.PaymentApiException;
import org.killbill.billing.payment.api.PaymentMethod;
import org.killbill.billing.payment.api.PaymentTransaction;
import org.killbill.billing.payment.api.PluginProperty;
import org.killbill.billing.payment.api.TransactionType;
import org.killbill.billing.payment.plugin.api.PaymentPluginStatus;
import org.killbill.billing.plugin.adyen.api.AdyenCallContext;
import org.killbill.billing.plugin.adyen.api.AdyenPaymentPluginApi;
import org.killbill.billing.plugin.adyen.client.model.NotificationItem;
import org.killbill.billing.plugin.adyen.client.notification.AdyenNotificationHandler;
import org.killbill.billing.plugin.adyen.dao.AdyenDao;
import org.killbill.billing.plugin.adyen.dao.gen.tables.records.AdyenHppRequestsRecord;
import org.killbill.billing.plugin.adyen.dao.gen.tables.records.AdyenResponsesRecord;
import org.killbill.billing.plugin.api.PluginProperties;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.billing.util.callcontext.TenantContext;
import org.killbill.clock.Clock;
import org.killbill.killbill.osgi.libs.killbill.OSGIKillbillAPI;

import com.google.common.base.MoreObjects;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

public class KillbillAdyenNotificationHandler implements AdyenNotificationHandler {

    private final OSGIKillbillAPI osgiKillbillAPI;
    private final AdyenDao dao;
    private final Clock clock;

    public KillbillAdyenNotificationHandler(final OSGIKillbillAPI osgiKillbillAPI, final AdyenDao dao, final Clock clock) {
        this.osgiKillbillAPI = osgiKillbillAPI;
        this.dao = dao;
        this.clock = clock;
    }

    @Override
    public boolean canHandleNotification(final NotificationRequestItem item) {
        // Handle them all
        return true;
    }

    @Override
    public void authorisationSuccess(final NotificationRequestItem item) {
        handleNotification(TransactionType.AUTHORIZE, item);
    }

    @Override
    public void authorisationFailure(final NotificationRequestItem item) {
        handleNotification(TransactionType.AUTHORIZE, item);
    }

    @Override
    public void captureSuccess(final NotificationRequestItem item) {
        handleNotification(TransactionType.CAPTURE, item);
    }

    @Override
    public void captureFailure(final NotificationRequestItem item) {
        handleNotification(TransactionType.CAPTURE, item);
    }

    @Override
    public void captureFailed(final NotificationRequestItem item) {
        handleNotification(TransactionType.CAPTURE, item);
    }

    @Override
    public void cancellationSuccess(final NotificationRequestItem item) {
        handleNotification(TransactionType.VOID, item);
    }

    @Override
    public void cancellationFailure(final NotificationRequestItem item) {
        handleNotification(TransactionType.VOID, item);
    }

    @Override
    public void chargeback(final NotificationRequestItem item) {
        handleChargebackNotification(item, true);
    }

    @Override
    public void chargebackReversed(final NotificationRequestItem item) {
        // TODO
        handleNotification(TransactionType.CHARGEBACK, item);
    }

    @Override
    public void refundSuccess(final NotificationRequestItem item) {
        handleNotification(TransactionType.REFUND, item);
    }

    @Override
    public void refundFailure(final NotificationRequestItem item) {
        handleNotification(TransactionType.REFUND, item);
    }

    @Override
    public void refundedReversed(final NotificationRequestItem item) {
        handleNotification(TransactionType.REFUND, item);
    }

    @Override
    public void refundFailed(final NotificationRequestItem item) {
        handleNotification(TransactionType.REFUND, item);
    }

    @Override
    public void notificationOfChargeback(final NotificationRequestItem item) {
        handleChargebackNotification(item, true);
    }

    @Override
    public void cancelOrRefundSuccess(final NotificationRequestItem item) {
        // TODO
        handleNotification(item);
    }

    @Override
    public void notificationOfFraud(final NotificationRequestItem item) {
        handleNotification(item);
    }

    @Override
    public void requestForInformation(final NotificationRequestItem item) {
        // TODO New chargeback?
        handleNotification(item);
    }

    @Override
    public void cancelOrRefundFailure(final NotificationRequestItem item) {
        // TODO
        handleNotification(item);
    }

    @Override
    public void dispute(final NotificationRequestItem item) {
        handleNotification(item);
    }

    @Override
    public void reportAvailable(final NotificationRequestItem item) {
        handleNotification(item);
    }

    @Override
    public void notificationtest(final NotificationRequestItem item) {
        handleNotification(item);
    }

    @Override
    public void recurringReceived(final NotificationRequestItem item) {
        handleNotification(item);
    }

    @Override
    public void cancelReceived(final NotificationRequestItem item) {
        handleNotification(item);
    }

    @Override
    public void recurringDetailDisabled(final NotificationRequestItem item) {
        handleNotification(item);
    }

    @Override
    public void recurringForUserDisabled(final NotificationRequestItem item) {
        handleNotification(item);
    }

    private void handleNotification(final NotificationRequestItem item) {
        handleNotification(null, item);
    }

    private void handleChargebackNotification(final NotificationRequestItem item, boolean success) {
        final NotificationItem notification = new NotificationItem(item);
        final DateTime utcNow = clock.getUTCNow();

        final AdyenResponsesRecord originalRecord = getResponseRecord(item.getOriginalReference());
        if (originalRecord == null) {
            recordNotification(null, null, null, TransactionType.CHARGEBACK, notification, utcNow, null);
            return;
        }

        final UUID kbAccountId = UUID.fromString(originalRecord.getKbAccountId());
        final UUID kbTenantId = UUID.fromString(originalRecord.getKbTenantId());
        final UUID kbPaymentId = UUID.fromString(originalRecord.getKbPaymentId());

        final CallContext context = new AdyenCallContext(utcNow, kbTenantId);
        final Account account = getAccount(kbAccountId, context);

        UUID kbPaymentTransactionId = null;
        try {
            final Payment payment = osgiKillbillAPI.getPaymentApi().createChargeback(account, kbPaymentId, notification.getAmount(), Currency.valueOf(notification.getCurrency()), null, context);
            final PaymentTransaction lastTransaction = filterForLastTransaction(payment);
            if (TransactionType.CHARGEBACK.equals(lastTransaction.getTransactionType())) {
                kbPaymentTransactionId = lastTransaction.getId();
            }
        } catch (PaymentApiException e) {
            throw new RuntimeException("It was not possible to create chargeback!", e);
        } finally {
            recordNotification(kbAccountId, kbPaymentId, kbPaymentTransactionId, TransactionType.CHARGEBACK, notification, utcNow, kbTenantId);
        }
    }

    private void handleNotification(@Nullable final TransactionType transactionType, final NotificationRequestItem item) {
        final NotificationItem notification = new NotificationItem(item);
        final DateTime utcNow = clock.getUTCNow();

        UUID kbAccountId = null;
        UUID kbPaymentId = null;
        UUID kbPaymentTransactionId = null;
        UUID kbTenantId = null;
        try {
            AdyenHppRequestsRecord hppRequest = null;

            // First, determine if we already have a payment for that notification
            final AdyenResponsesRecord record = getResponseRecord(item.getPspReference());
            if (record != null) {
                kbAccountId = UUID.fromString(record.getKbAccountId());
                kbTenantId = UUID.fromString(record.getKbTenantId());
                kbPaymentId = UUID.fromString(record.getKbPaymentId());
                kbPaymentTransactionId = UUID.fromString(record.getKbPaymentTransactionId());
            } else {
                hppRequest = getHppRequest(notification);
                if (hppRequest != null) {
                    kbAccountId = UUID.fromString(hppRequest.getKbAccountId());
                    kbTenantId = UUID.fromString(hppRequest.getKbTenantId());
                    if (hppRequest.getKbPaymentId() != null) {
                        kbPaymentId = UUID.fromString(hppRequest.getKbPaymentId());
                    }
                    if (hppRequest.getKbPaymentTransactionId() != null) {
                        kbPaymentTransactionId = UUID.fromString(hppRequest.getKbPaymentTransactionId());
                    }
                }
                // Otherwise, maybe REPORT_AVAILABLE notification?
            }

            if (kbAccountId != null && kbTenantId != null) {
                // Retrieve the account
                final CallContext context = new AdyenCallContext(utcNow, kbTenantId);
                final Account account = getAccount(kbAccountId, context);
                final boolean isSuccess = MoreObjects.firstNonNull(notification.getSuccess(), false);
                final boolean isHpp = hppRequest != null;

                // Update Kill Bill
                if (kbPaymentTransactionId != null) {
                    updateResponse(kbPaymentTransactionId, notification, isSuccess, isHpp, kbTenantId);
                    notifyKillBill(account, kbPaymentTransactionId, notification, isSuccess, context);
                } else {
                    final Payment payment = recordPayment(account, notification, isSuccess, isHpp, context);
                    kbPaymentId = payment.getId();
                    kbPaymentTransactionId = payment.getTransactions().iterator().next().getId();
                }
            }
        } finally {
            recordNotification(kbAccountId, kbPaymentId, kbPaymentTransactionId, transactionType, notification, utcNow, kbTenantId);
        }
    }

    private AdyenResponsesRecord getResponseRecord(final String pspReference) {
        try {
            return dao.getResponse(pspReference);
        } catch (final SQLException e) {
            // Have Adyen retry
            throw new RuntimeException("Unable to retrieve response for pspReference " + pspReference, e);
        }
    }

    private AdyenHppRequestsRecord getHppRequest(final NotificationItem notification) {
        try {
            return dao.getHppRequest(notification.getMerchantReference());
        } catch (final SQLException e) {
            throw new RuntimeException("Unable to retrieve HPP request for merchantReference " + notification.getMerchantReference(), e);
        }
    }

    private Account getAccount(final UUID kbAccountId, final CallContext context) {
        try {
            return osgiKillbillAPI.getAccountUserApi().getAccountById(kbAccountId, context);
        } catch (final AccountApiException e) {
            // Have Adyen retry
            throw new RuntimeException("Failed to retrieve account " + kbAccountId, e);
        }
    }

    private void notifyKillBill(final Account account, final UUID kbPaymentTransactionId, final NotificationItem notification, final boolean isSuccess, final CallContext context) {
        try {
            osgiKillbillAPI.getPaymentApi().notifyPendingTransactionOfStateChanged(account, kbPaymentTransactionId, isSuccess, context);
        } catch (final PaymentApiException e) {
            // PAYMENT_NO_SUCH_SUCCESS_PAYMENT means the payment wasn't PENDING (e.g. for authorizations)
            if (e.getCode() != ErrorCode.PAYMENT_NO_SUCH_SUCCESS_PAYMENT.getCode()) {
                // Have Adyen retry
                throw new RuntimeException("Failed to notify Kill Bill for kbPaymentTransactionId " + kbPaymentTransactionId, e);
            }
        }
    }

    private Payment recordPayment(final Account account, final NotificationItem notification, final boolean isSuccess, final boolean isHpp, final CallContext context) {
        final UUID kbPaymentMethodId = getAdyenKbPaymentMethodId(account.getId(), context);
        final UUID kbPaymentId = null;
        final BigDecimal amount = notification.getAmount();
        final Currency currency = Currency.valueOf(notification.getCurrency());
        final String paymentExternalKey = notification.getMerchantReference();
        final String paymentTransactionExternalKey = notification.getMerchantReference();

        final ImmutableMap.Builder<String, Object> pluginPropertiesMapBuilder = new ImmutableMap.Builder<String, Object>();
        pluginPropertiesMapBuilder.put(AdyenPaymentPluginApi.PROPERTY_FROM_HPP, isHpp);
        if (isHpp) {
            pluginPropertiesMapBuilder.put(AdyenPaymentPluginApi.PROPERTY_FROM_HPP_TRANSACTION_STATUS, isSuccess ? PaymentPluginStatus.PROCESSED.toString() : PaymentPluginStatus.ERROR.toString());
        }
        pluginPropertiesMapBuilder.put(AdyenPaymentPluginApi.PROPERTY_MERCHANT_REFERENCE, notification.getMerchantReference());
        pluginPropertiesMapBuilder.put(AdyenPaymentPluginApi.PROPERTY_PSP_REFERENCE, notification.getPspReference());
        pluginPropertiesMapBuilder.put(AdyenPaymentPluginApi.PROPERTY_AMOUNT, amount);
        pluginPropertiesMapBuilder.put(AdyenPaymentPluginApi.PROPERTY_CURRENCY, currency);

        if (notification.getAdditionalData() != null) {
            pluginPropertiesMapBuilder.put(AdyenPaymentPluginApi.PROPERTY_ADDITIONAL_DATA, notification.getAdditionalData());
        }
        if (notification.getEventCode() != null) {
            pluginPropertiesMapBuilder.put(AdyenPaymentPluginApi.PROPERTY_EVENT_CODE, notification.getEventCode());
        }
        if (notification.getEventDate() != null) {
            pluginPropertiesMapBuilder.put(AdyenPaymentPluginApi.PROPERTY_EVENT_DATE, notification.getEventDate());
        }
        if (notification.getMerchantAccountCode() != null) {
            pluginPropertiesMapBuilder.put(AdyenPaymentPluginApi.PROPERTY_MERCHANT_ACCOUNT_CODE, notification.getMerchantAccountCode());
        }
        if (notification.getOperations() != null) {
            pluginPropertiesMapBuilder.put(AdyenPaymentPluginApi.PROPERTY_OPERATIONS, notification.getOperations());
        }
        if (notification.getOriginalReference() != null) {
            pluginPropertiesMapBuilder.put(AdyenPaymentPluginApi.PROPERTY_ORIGINAL_REFERENCE, notification.getOriginalReference());
        }
        if (notification.getPaymentMethod() != null) {
            pluginPropertiesMapBuilder.put(AdyenPaymentPluginApi.PROPERTY_PAYMENT_METHOD, notification.getPaymentMethod());
        }
        if (notification.getReason() != null) {
            pluginPropertiesMapBuilder.put(AdyenPaymentPluginApi.PROPERTY_REASON, notification.getReason());
        }
        if (notification.getSuccess() != null) {
            pluginPropertiesMapBuilder.put(AdyenPaymentPluginApi.PROPERTY_SUCCESS, notification.getSuccess());
        }
        final ImmutableMap<String, Object> purchasePropertiesMap = pluginPropertiesMapBuilder.build();
        final Iterable<PluginProperty> purchaseProperties = PluginProperties.buildPluginProperties(purchasePropertiesMap);

        try {
            return osgiKillbillAPI.getPaymentApi().createPurchase(account,
                                                                  kbPaymentMethodId,
                                                                  kbPaymentId,
                                                                  amount,
                                                                  currency,
                                                                  paymentExternalKey,
                                                                  paymentTransactionExternalKey,
                                                                  purchaseProperties,
                                                                  context);
        } catch (final PaymentApiException e) {
            // Have Adyen retry
            throw new RuntimeException("Failed to record purchase", e);
        }
    }

    private UUID getAdyenKbPaymentMethodId(final UUID kbAccountId, final TenantContext context) {
        try {
            return Iterables.<PaymentMethod>find(osgiKillbillAPI.getPaymentApi().getAccountPaymentMethods(kbAccountId, false, ImmutableList.<PluginProperty>of(), context),
                                                 new Predicate<PaymentMethod>() {
                                                     @Override
                                                     public boolean apply(final PaymentMethod paymentMethod) {
                                                         return AdyenActivator.PLUGIN_NAME.equals(paymentMethod.getPluginName());
                                                     }
                                                 }).getId();
        } catch (final PaymentApiException e) {
            // Have Adyen retry
            throw new RuntimeException("Failed to locate Adyen payment method for account " + kbAccountId, e);
        }
    }

    private void recordNotification(@Nullable final UUID kbAccountId,
                                    @Nullable final UUID kbPaymentId,
                                    @Nullable final UUID kbPaymentTransactionId,
                                    @Nullable final TransactionType transactionType,
                                    final NotificationItem notification,
                                    final DateTime utcNow,
                                    final UUID kbTenantId) {
        try {
            dao.addNotification(kbAccountId, kbPaymentId, kbPaymentTransactionId, transactionType, notification, utcNow, kbTenantId);
        } catch (final SQLException e) {
            // Have Adyen retry
            throw new RuntimeException("Unable to record notification " + notification, e);
        }
    }

    private void updateResponse(final UUID kbTransactionId, final NotificationItem notification, final boolean isSuccess, final boolean isHpp, final UUID kbTenantId) {
        final ImmutableMap.Builder<String, String> properties = new ImmutableMap.Builder<String, String>();
        properties.put(AdyenPaymentPluginApi.PROPERTY_PSP_REFERENCE, notification.getPspReference());
        if (isHpp) {
            properties.put(AdyenPaymentPluginApi.PROPERTY_FROM_HPP_TRANSACTION_STATUS, isSuccess ? PaymentPluginStatus.PROCESSED.toString() : PaymentPluginStatus.ERROR.toString());
        }
        final Iterable<PluginProperty> pluginProperties = PluginProperties.buildPluginProperties(properties.build());
        try {
            dao.updateResponse(kbTransactionId, pluginProperties, kbTenantId);
        } catch (final SQLException e) {
            // Have Adyen retry
            throw new RuntimeException("Unable to update response " + notification, e);
        }
    }

    private PaymentTransaction filterForLastTransaction(Payment payment) {
        int numberOfTransaction = payment.getTransactions().size();
        return payment.getTransactions().get(numberOfTransaction - 1);
    }
}
