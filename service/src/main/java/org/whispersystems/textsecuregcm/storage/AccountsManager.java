/*
 * Copyright 2013-2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.storage;


import static com.codahale.metrics.MetricRegistry.name;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.Timer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisException;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.auth.AuthenticationCredentials;
import org.whispersystems.textsecuregcm.entities.AccountAttributes;
import org.whispersystems.textsecuregcm.push.ClientPresenceManager;
import org.whispersystems.textsecuregcm.redis.FaultTolerantRedisCluster;
import org.whispersystems.textsecuregcm.redis.RedisOperation;
import org.whispersystems.textsecuregcm.securebackup.SecureBackupClient;
import org.whispersystems.textsecuregcm.securestorage.SecureStorageClient;
import org.whispersystems.textsecuregcm.sqs.DirectoryQueue;
import org.whispersystems.textsecuregcm.util.Constants;
import org.whispersystems.textsecuregcm.util.SystemMapper;
import org.whispersystems.textsecuregcm.util.UsernameValidator;
import org.whispersystems.textsecuregcm.util.Util;

public class AccountsManager {

  private static final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(Constants.METRICS_NAME);
  private static final Timer createTimer = metricRegistry.timer(name(AccountsManager.class, "create"));
  private static final Timer updateTimer = metricRegistry.timer(name(AccountsManager.class, "update"));
  private static final Timer getByNumberTimer = metricRegistry.timer(name(AccountsManager.class, "getByNumber"));
  private static final Timer getByUsernameTimer = metricRegistry.timer(name(AccountsManager.class, "getByUsername"));
  private static final Timer getByUuidTimer = metricRegistry.timer(name(AccountsManager.class, "getByUuid"));
  private static final Timer deleteTimer = metricRegistry.timer(name(AccountsManager.class, "delete"));

  private static final Timer redisSetTimer = metricRegistry.timer(name(AccountsManager.class, "redisSet"));
  private static final Timer redisNumberGetTimer = metricRegistry.timer(name(AccountsManager.class, "redisNumberGet"));
  private static final Timer redisUsernameGetTimer = metricRegistry.timer(name(AccountsManager.class, "redisUsernameGet"));
  private static final Timer redisPniGetTimer = metricRegistry.timer(name(AccountsManager.class, "redisPniGet"));
  private static final Timer redisUuidGetTimer = metricRegistry.timer(name(AccountsManager.class, "redisUuidGet"));
  private static final Timer redisDeleteTimer = metricRegistry.timer(name(AccountsManager.class, "redisDelete"));

  private static final String CREATE_COUNTER_NAME       = name(AccountsManager.class, "createCounter");
  private static final String DELETE_COUNTER_NAME       = name(AccountsManager.class, "deleteCounter");
  private static final String COUNTRY_CODE_TAG_NAME     = "country";
  private static final String DELETION_REASON_TAG_NAME  = "reason";

  private final Logger logger = LoggerFactory.getLogger(AccountsManager.class);

  private final Accounts accounts;
  private final PhoneNumberIdentifiers phoneNumberIdentifiers;
  private final FaultTolerantRedisCluster cacheCluster;
  private final DeletedAccountsManager deletedAccountsManager;
  private final DirectoryQueue directoryQueue;
  private final Keys keys;
  private final MessagesManager messagesManager;
  private final ReservedUsernames reservedUsernames;
  private final ProfilesManager profilesManager;
  private final StoredVerificationCodeManager pendingAccounts;
  private final SecureStorageClient secureStorageClient;
  private final SecureBackupClient secureBackupClient;
  private final ClientPresenceManager clientPresenceManager;
  private final ObjectMapper mapper;
  private final Clock clock;

  // An account that's used at least daily will get reset in the cache at least once per day when its "last seen"
  // timestamp updates; expiring entries after two days will help clear out "zombie" cache entries that are read
  // frequently (e.g. the account is in an active group and receives messages frequently), but aren't actively used by
  // the owner.
  private static final long CACHE_TTL_SECONDS = Duration.ofDays(2).toSeconds();

  @FunctionalInterface
  private interface AccountPersister {
    void persistAccount(Account account) throws UsernameNotAvailableException;
  }

  public enum DeletionReason {
    ADMIN_DELETED("admin"),
    EXPIRED      ("expired"),
    USER_REQUEST ("userRequest");

    private final String tagValue;

    DeletionReason(final String tagValue) {
      this.tagValue = tagValue;
    }
  }

  public AccountsManager(final Accounts accounts,
      final PhoneNumberIdentifiers phoneNumberIdentifiers,
      final FaultTolerantRedisCluster cacheCluster,
      final DeletedAccountsManager deletedAccountsManager,
      final DirectoryQueue directoryQueue,
      final Keys keys,
      final MessagesManager messagesManager,
      final ReservedUsernames reservedUsernames,
      final ProfilesManager profilesManager,
      final StoredVerificationCodeManager pendingAccounts,
      final SecureStorageClient secureStorageClient,
      final SecureBackupClient secureBackupClient,
      final ClientPresenceManager clientPresenceManager,
      final Clock clock) {
    this.accounts = accounts;
    this.phoneNumberIdentifiers = phoneNumberIdentifiers;
    this.cacheCluster = cacheCluster;
    this.deletedAccountsManager = deletedAccountsManager;
    this.directoryQueue = directoryQueue;
    this.keys = keys;
    this.messagesManager = messagesManager;
    this.profilesManager = profilesManager;
    this.pendingAccounts = pendingAccounts;
    this.secureStorageClient = secureStorageClient;
    this.secureBackupClient  = secureBackupClient;
    this.clientPresenceManager = clientPresenceManager;
    this.reservedUsernames = reservedUsernames;
    this.mapper              = SystemMapper.getMapper();
    this.clock = Objects.requireNonNull(clock);
  }

  public Account create(final String number,
      final String password,
      final String signalAgent,
      final AccountAttributes accountAttributes,
      final List<AccountBadge> accountBadges) throws InterruptedException {

    try (Timer.Context ignored = createTimer.time()) {
      final Account account = new Account();

      deletedAccountsManager.lockAndTake(number, maybeRecentlyDeletedUuid -> {
        Device device = new Device();
        device.setId(Device.MASTER_ID);
        device.setAuthenticationCredentials(new AuthenticationCredentials(password));
        device.setFetchesMessages(accountAttributes.getFetchesMessages());
        device.setRegistrationId(accountAttributes.getRegistrationId());
        device.setName(accountAttributes.getName());
        device.setCapabilities(accountAttributes.getCapabilities());
        device.setCreated(System.currentTimeMillis());
        device.setLastSeen(Util.todayInMillis());
        device.setUserAgent(signalAgent);

        account.setNumber(number, phoneNumberIdentifiers.getPhoneNumberIdentifier(number));
        account.setUuid(maybeRecentlyDeletedUuid.orElseGet(UUID::randomUUID));
        account.addDevice(device);
        account.setRegistrationLockFromAttributes(accountAttributes);
        account.setUnidentifiedAccessKey(accountAttributes.getUnidentifiedAccessKey());
        account.setUnrestrictedUnidentifiedAccess(accountAttributes.isUnrestrictedUnidentifiedAccess());
        account.setDiscoverableByPhoneNumber(accountAttributes.isDiscoverableByPhoneNumber());
        account.setBadges(clock, accountBadges);

        final UUID originalUuid = account.getUuid();

        boolean freshUser = accounts.create(account);

        // create() sometimes updates the UUID, if there was a number conflict.
        // for metrics, we want secondary to run with the same original UUID
        final UUID actualUuid = account.getUuid();

        redisSet(account);

        pendingAccounts.remove(number);

        // In terms of previously-existing accounts, there are three possible cases:
        //
        // 1. This is a completely new account; there was no pre-existing account and no recently-deleted account
        // 2. This is a re-registration of an existing account. The storage layer will update the existing account in
        //    place to match the account record created above, and will update the UUID of the newly-created account
        //    instance to match the stored account record (i.e. originalUuid != actualUuid).
        // 3. This is a re-registration of a recently-deleted account, in which case maybeRecentlyDeletedUuid is
        //    present.
        //
        // All cases are mutually-exclusive. In the first case, we don't need to do anything. In the third, we can be
        // confident that everything has already been deleted. In the second case, though, we're taking over an existing
        // account and need to clear out messages and keys that may have been stored for the old account.
        if (!originalUuid.equals(actualUuid)) {
          messagesManager.clear(actualUuid);
          keys.delete(actualUuid);
          profilesManager.deleteAll(actualUuid);
        }

        final Tags tags;

        if (freshUser) {
          tags = Tags.of("type", "new");
        } else if (!originalUuid.equals(actualUuid)) {
          tags = Tags.of("type", "re-registration");
        } else {
          tags = Tags.of("type", "recently-deleted");
        }

        Metrics.counter(CREATE_COUNTER_NAME, tags).increment();

        if (!account.isDiscoverableByPhoneNumber()) {
          // The newly-created account has explicitly opted out of discoverability
          directoryQueue.deleteAccount(account);
        }
      });

      return account;
    }
  }

  public Account changeNumber(final Account account, final String number) throws InterruptedException {
    final String originalNumber = account.getNumber();

    if (originalNumber.equals(number)) {
      return account;
    }

    final AtomicReference<Account> updatedAccount = new AtomicReference<>();

    deletedAccountsManager.lockAndPut(account.getNumber(), number, (originalAci, deletedAci) -> {
      redisDelete(account);

      final Optional<Account> maybeExistingAccount = getByE164(number);
      final Optional<UUID> displacedUuid;

      if (maybeExistingAccount.isPresent()) {
        delete(maybeExistingAccount.get());
        directoryQueue.deleteAccount(maybeExistingAccount.get());
        displacedUuid = maybeExistingAccount.map(Account::getUuid);
      } else {
        displacedUuid = deletedAci;
      }

      final UUID uuid = account.getUuid();
      final UUID phoneNumberIdentifier = phoneNumberIdentifiers.getPhoneNumberIdentifier(number);

      final Account numberChangedAccount;

      try {
        numberChangedAccount = updateWithRetries(
            account,
            a -> true,
            a -> accounts.changeNumber(a, number, phoneNumberIdentifier),
            () -> accounts.getByAccountIdentifier(uuid).orElseThrow());
      } catch (UsernameNotAvailableException e) {
        // This should never happen when changing numbers
        throw new RuntimeException(e);
      }

      updatedAccount.set(numberChangedAccount);
      directoryQueue.changePhoneNumber(numberChangedAccount, originalNumber, number);

      return displacedUuid;
    });

    return updatedAccount.get();
  }

  public Account setUsername(final Account account, final String username) throws UsernameNotAvailableException {
    final String canonicalUsername = UsernameValidator.getCanonicalUsername(username);

    if (account.getUsername().map(canonicalUsername::equals).orElse(false)) {
      return account;
    }

    if (reservedUsernames.isReserved(canonicalUsername, account.getUuid())) {
      throw new UsernameNotAvailableException();
    }

    redisDelete(account);

    return updateWithRetries(
        account,
        a -> true,
        a -> accounts.setUsername(a, canonicalUsername),
        () -> accounts.getByAccountIdentifier(account.getUuid()).orElseThrow());
  }

  public Account clearUsername(final Account account) {
    redisDelete(account);

    try {
      return updateWithRetries(
          account,
          a -> true,
          accounts::clearUsername,
          () -> accounts.getByAccountIdentifier(account.getUuid()).orElseThrow());
    } catch (UsernameNotAvailableException e) {
      // This should never happen
      throw new RuntimeException(e);
    }
  }

  public Account update(Account account, Consumer<Account> updater) {

    try {
      return update(account, a -> {
        updater.accept(a);
        // assume that all updaters passed to the public method actually modify the account
        return true;
      });
    } catch (UsernameNotAvailableException e) {
      // This should never happen for general-purpose, public account updates
      throw new RuntimeException(e);
    }
  }

  /**
   * Specialized version of {@link #updateDevice(Account, long, Consumer)} that minimizes potentially contentious and
   * redundant updates of {@code device.lastSeen}
   */
  public Account updateDeviceLastSeen(Account account, Device device, final long lastSeen) {

    try {
      return update(account, a -> {

        final Optional<Device> maybeDevice = a.getDevice(device.getId());

        return maybeDevice.map(d -> {
          if (d.getLastSeen() >= lastSeen) {
            return false;
          }

          d.setLastSeen(lastSeen);

          return true;

        }).orElse(false);
      });
    } catch (UsernameNotAvailableException e) {
      // This should never happen when updating last-seen timestamps
      throw new RuntimeException(e);
    }
  }

  /**
   * @param account account to update
   * @param updater must return {@code true} if the account was actually updated
   */
  private Account update(Account account, Function<Account, Boolean> updater) throws UsernameNotAvailableException {

    final boolean wasVisibleBeforeUpdate = account.shouldBeVisibleInDirectory();

    final Account updatedAccount;

    try (Timer.Context ignored = updateTimer.time()) {

      redisDelete(account);

      final UUID uuid = account.getUuid();
      final String originalNumber = account.getNumber();
      final UUID originalPhoneNumberIdentifier = account.getPhoneNumberIdentifier();
      final Optional<String> originalUsername = account.getUsername();

      updatedAccount = updateWithRetries(account,
          updater,
          accounts::update,
          () -> accounts.getByAccountIdentifier(uuid).orElseThrow());

      assert updatedAccount.getNumber().equals(originalNumber);

      if (!updatedAccount.getNumber().equals(originalNumber)) {
        logger.error("Account number changed via \"normal\" update; numbers must be changed via changeNumber method",
            new RuntimeException());
      }

      assert updatedAccount.getPhoneNumberIdentifier().equals(originalPhoneNumberIdentifier);

      if (!updatedAccount.getPhoneNumberIdentifier().equals(originalPhoneNumberIdentifier)) {
        logger.error("Phone number identifier changed via \"normal\" update; PNIs must be changed via changeNumber method",
            new RuntimeException());
      }

      assert updatedAccount.getUsername().equals(originalUsername);

      if (!updatedAccount.getUsername().equals(originalUsername)) {
        logger.error("Username changed via \"normal\" update; usernames must be changed via setUsername method",
            new RuntimeException());
      }

      redisSet(updatedAccount);
    }

    final boolean isVisibleAfterUpdate = updatedAccount.shouldBeVisibleInDirectory();

    if (wasVisibleBeforeUpdate != isVisibleAfterUpdate) {
      directoryQueue.refreshAccount(updatedAccount);
    }

    return updatedAccount;
  }

  private Account updateWithRetries(Account account, Function<Account, Boolean> updater, AccountPersister persister,
      Supplier<Account> retriever) throws UsernameNotAvailableException {

    if (!updater.apply(account)) {
      return account;
    }

    final int maxTries = 10;
    int tries = 0;

    while (tries < maxTries) {

      try {
        persister.persistAccount(account);

        final Account updatedAccount;
        try {
          updatedAccount = mapper.readValue(mapper.writeValueAsBytes(account), Account.class);
          updatedAccount.setUuid(account.getUuid());
        } catch (final IOException e) {
          // this should really, truly, never happen
          throw new IllegalArgumentException(e);
        }

        account.markStale();

        return updatedAccount;
      } catch (final ContestedOptimisticLockException e) {
        tries++;
        account = retriever.get();

        if (!updater.apply(account)) {
          return account;
        }
      }
    }

    throw new OptimisticLockRetryLimitExceededException();
  }

  public Account updateDevice(Account account, long deviceId, Consumer<Device> deviceUpdater) {
    try {
      return update(account, a -> {
        a.getDevice(deviceId).ifPresent(deviceUpdater);
        // assume that all updaters passed to the public method actually modify the device
        return true;
      });
    } catch (UsernameNotAvailableException e) {
      // This should never happen when updating devices
      throw new RuntimeException(e);
    }
  }

  public Optional<Account> getByE164(String number) {
    try (Timer.Context ignored = getByNumberTimer.time()) {
      Optional<Account> account = redisGetByE164(number);

      if (account.isEmpty()) {
        account = accounts.getByE164(number);
        account.ifPresent(this::redisSet);
      }

      return account;
    }
  }

  public Optional<Account> getByPhoneNumberIdentifier(UUID pni) {
    try (Timer.Context ignored = getByNumberTimer.time()) {
      Optional<Account> account = redisGetByPhoneNumberIdentifier(pni);

      if (account.isEmpty()) {
        account = accounts.getByPhoneNumberIdentifier(pni);
        account.ifPresent(this::redisSet);
      }

      return account;
    }
  }

  public Optional<Account> getByUsername(final String username) {
    try (final Timer.Context ignored = getByUsernameTimer.time()) {
      final String canonicalUsername = UsernameValidator.getCanonicalUsername(username);

      Optional<Account> account = redisGetByUsername(canonicalUsername);

      if (account.isEmpty()) {
        account = accounts.getByUsername(canonicalUsername);
        account.ifPresent(this::redisSet);
      }

      return account;
    }
  }

  public Optional<Account> getByAccountIdentifier(UUID uuid) {
    try (Timer.Context ignored = getByUuidTimer.time()) {
      Optional<Account> account = redisGetByAccountIdentifier(uuid);

      if (account.isEmpty()) {
        account = accounts.getByAccountIdentifier(uuid);
        account.ifPresent(this::redisSet);
      }

      return account;
    }
  }

  public AccountCrawlChunk getAllFromDynamo(int length) {
    return accounts.getAllFromStart(length);
  }

  public AccountCrawlChunk getAllFromDynamo(UUID uuid, int length) {
    return accounts.getAllFrom(uuid, length);
  }

  public void delete(final Account account, final DeletionReason deletionReason) throws InterruptedException {
    try (final Timer.Context ignored = deleteTimer.time()) {
      deletedAccountsManager.lockAndPut(account.getNumber(), () -> {
        delete(account);
        directoryQueue.deleteAccount(account);

        return account.getUuid();
      });
    } catch (final RuntimeException | InterruptedException e) {
      logger.warn("Failed to delete account", e);
      throw e;
    }

    Metrics.counter(DELETE_COUNTER_NAME,
        COUNTRY_CODE_TAG_NAME, Util.getCountryCode(account.getNumber()),
        DELETION_REASON_TAG_NAME, deletionReason.tagValue)
        .increment();
  }

  private void delete(final Account account) {
    final CompletableFuture<Void> deleteStorageServiceDataFuture = secureStorageClient.deleteStoredData(account.getUuid());
    final CompletableFuture<Void> deleteBackupServiceDataFuture = secureBackupClient.deleteBackups(account.getUuid());

    profilesManager.deleteAll(account.getUuid());
    keys.delete(account.getUuid());
    keys.delete(account.getPhoneNumberIdentifier());
    messagesManager.clear(account.getUuid());
    messagesManager.clear(account.getPhoneNumberIdentifier());

    deleteStorageServiceDataFuture.join();
    deleteBackupServiceDataFuture.join();

    accounts.delete(account.getUuid());
    redisDelete(account);

    RedisOperation.unchecked(() ->
        account.getDevices().forEach(device ->
            clientPresenceManager.disconnectPresence(account.getUuid(), device.getId())));
  }

  private String getAccountMapKey(String key) {
    return "AccountMap::" + key;
  }

  private String getAccountEntityKey(UUID uuid) {
    return "Account3::" + uuid.toString();
  }

  private void redisSet(Account account) {
    try (Timer.Context ignored = redisSetTimer.time()) {
      final String accountJson = mapper.writeValueAsString(account);

      cacheCluster.useCluster(connection -> {
        final RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        commands.setex(getAccountMapKey(account.getPhoneNumberIdentifier().toString()), CACHE_TTL_SECONDS, account.getUuid().toString());
        commands.setex(getAccountMapKey(account.getNumber()), CACHE_TTL_SECONDS, account.getUuid().toString());
        commands.setex(getAccountEntityKey(account.getUuid()), CACHE_TTL_SECONDS, accountJson);

        account.getUsername().ifPresent(username ->
            commands.setex(getAccountMapKey(username), CACHE_TTL_SECONDS, account.getUuid().toString()));
      });
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private Optional<Account> redisGetByPhoneNumberIdentifier(UUID uuid) {
    return redisGetBySecondaryKey(uuid.toString(), redisPniGetTimer);
  }

  private Optional<Account> redisGetByE164(String e164) {
    return redisGetBySecondaryKey(e164, redisNumberGetTimer);
  }

  private Optional<Account> redisGetByUsername(String username) {
    return redisGetBySecondaryKey(username, redisUsernameGetTimer);
  }

  private Optional<Account> redisGetBySecondaryKey(String secondaryKey, Timer timer) {
    try (Timer.Context ignored = timer.time()) {
      final String uuid = cacheCluster.withCluster(connection -> connection.sync().get(getAccountMapKey(secondaryKey)));

      if (uuid != null) return redisGetByAccountIdentifier(UUID.fromString(uuid));
      else              return Optional.empty();
    } catch (IllegalArgumentException e) {
      logger.warn("Deserialization error", e);
      return Optional.empty();
    } catch (RedisException e) {
      logger.warn("Redis failure", e);
      return Optional.empty();
    }
  }

  private Optional<Account> redisGetByAccountIdentifier(UUID uuid) {
    try (Timer.Context ignored = redisUuidGetTimer.time()) {
      final String json = cacheCluster.withCluster(connection -> connection.sync().get(getAccountEntityKey(uuid)));

      if (json != null) {
        Account account = mapper.readValue(json, Account.class);
        account.setUuid(uuid);

        if (account.getPhoneNumberIdentifier() == null) {
          logger.warn("Account {} loaded from Redis is missing a PNI", uuid);
        }

        return Optional.of(account);
      }

      return Optional.empty();
    } catch (IOException e) {
      logger.warn("Deserialization error", e);
      return Optional.empty();
    } catch (RedisException e) {
      logger.warn("Redis failure", e);
      return Optional.empty();
    }
  }

  private void redisDelete(final Account account) {
    try (final Timer.Context ignored = redisDeleteTimer.time()) {
      cacheCluster.useCluster(connection -> {
        connection.sync().del(
            getAccountMapKey(account.getNumber()),
            getAccountMapKey(account.getPhoneNumberIdentifier().toString()),
            getAccountEntityKey(account.getUuid()));

        account.getUsername().ifPresent(username -> connection.sync().del(getAccountMapKey(username)));
      });
    }
  }
}
