package io.tgmcp.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Security-audit follow-up: docs/production-prerequisites.md has long documented that the
 * in-memory {@link io.tgmcp.ratelimit.RateLimiter} budget and {@link
 * io.tgmcp.sync.SyncScheduler}'s per-chat sync cadence both carry a "single running instance"
 * assumption that nothing in this codebase actually enforced -- scaling a hosting platform's
 * replica count above one (a dashboard setting entirely outside this codebase's control) would
 * silently let two processes each believe they alone own a tenant's TDLib session, corrupting
 * {@code TdlibTelegramGateway.currentOwner}, the rate limiter's budget, and the sync scheduler's
 * cadence, with no error surfaced anywhere until something behaved strangely.
 *
 * <p>Fails the same way {@link ProductionSafetyValidator} does: loud and at startup, never
 * silent corruption discovered later. Holds a PostgreSQL session-level advisory lock ({@code
 * pg_try_advisory_lock}) on a single, fixed key for the lifetime of the process, using a
 * connection deliberately kept checked out of the connection pool for as long as this component
 * lives -- returning it to the pool (even via a supposedly clean {@code close()}, which a
 * pooled connection does not actually sever) would leave the lock held by a physical connection
 * this component no longer controls. A second instance started against the <em>same</em>
 * database that cannot acquire the lock refuses to start outright rather than running
 * alongside the first. A deliberate replacement (rolling deploy) is unaffected: the old
 * instance's connection, and with it the lock, is released via {@link #release()} as that
 * process shuts down, and even an unclean crash releases it as soon as PostgreSQL notices the
 * backend is gone.
 *
 * <p>Scoped to {@code realAccountAccessAuthorized}, like every other fail-fast check here: a
 * demo/{@code MOCK} deployment has no live TDLib session to corrupt this way and is free to run
 * scaled out.
 *
 * <p>Excluded from the {@code test} profile: the test suite deliberately starts many independent
 * Spring contexts (several of them with {@code realAccountAccessAuthorized=true}, to exercise
 * {@link ProductionSafetyValidator} and other real-account-only code paths) against one shared
 * test database. Those contexts are not competing production instances -- they are exactly the
 * kind of legitimate, sequential context churn this guard must not mistake for a second live
 * deployment.
 */
@Component
@Profile("!test")
public class SingleInstanceGuard {

    private static final Logger log = LoggerFactory.getLogger(SingleInstanceGuard.class);

    /**
     * Arbitrary, fixed, and namespaced to exactly this purpose. PostgreSQL advisory locks share
     * one flat 64-bit keyspace per database -- this key must never be reused for anything else.
     */
    static final long LOCK_KEY = 7_269_412_091_734_218_765L;

    private final Connection heldConnection;

    public SingleInstanceGuard(AppProperties properties, DataSource dataSource) throws SQLException {
        if (!properties.telegram().realAccountAccessAuthorized()) {
            this.heldConnection = null;
            return;
        }
        Connection connection = dataSource.getConnection();
        boolean acquired;
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                acquired = resultSet.getBoolean(1);
            }
        }
        if (!acquired) {
            connection.close();
            this.heldConnection = null;
            throw new IllegalStateException(
                    "Refusing to start: another instance already holds this database's single-instance "
                            + "advisory lock. This application's rate limiter, TDLib session ownership and "
                            + "sync scheduler all assume exactly one running instance per database -- running "
                            + "more than one against the same real-account database would silently corrupt "
                            + "all three (see docs/production-prerequisites.md). If you deliberately scaled "
                            + "to more than one replica, scale back to one. If the previous instance crashed "
                            + "without shutting down cleanly, this lock releases automatically as soon as "
                            + "PostgreSQL detects that its backend connection has actually disconnected.");
        }
        this.heldConnection = connection;
        log.info("Single-instance advisory lock acquired: this is the only instance permitted to run "
                + "against this database while real-account access is authorized.");
    }

    @PreDestroy
    void release() {
        if (this.heldConnection == null) {
            return;
        }
        // Explicitly unlocked rather than relying on close() alone: a pooled Connection's
        // close() returns it to the pool rather than actually severing the backend session, so
        // the session-level lock would otherwise still be held by a physical connection this
        // component no longer tracks.
        try (PreparedStatement statement = this.heldConnection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, LOCK_KEY);
            statement.execute();
        }
        catch (SQLException ex) {
            log.warn("Failed to explicitly release the single-instance advisory lock; PostgreSQL will "
                    + "still release it once it detects this backend has disconnected.", ex);
        }
        finally {
            try {
                this.heldConnection.close();
            }
            catch (SQLException ex) {
                log.warn("Failed to close the single-instance advisory lock connection cleanly.", ex);
            }
        }
    }
}
