package io.tgmcp.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import io.tgmcp.telegram.gateway.TelegramAdapterKind;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link SingleInstanceGuard} directly (a plain constructor call, no Spring context,
 * no real database), matching {@code ProductionSafetyValidatorTests}' own style for this kind of
 * fail-fast-at-startup component.
 */
class SingleInstanceGuardTests {

    private static AppProperties properties(boolean realAccountAccessAuthorized) {
        return new AppProperties(
                new AppProperties.Telegram(TelegramAdapterKind.TDLIB, "1", "hash", "./tdlib-data",
                        realAccountAccessAuthorized, true),
                new AppProperties.Mcp(50, 200, 20, 512, 20, 262_144, 4000, 366,
                        java.time.Duration.ofMinutes(15), 120, "http://localhost:8080/mcp", "mcp:read"),
                new AppProperties.Consent("2026-09-v1", true, java.time.Duration.ofDays(365)),
                new AppProperties.Sync(java.time.Duration.ofSeconds(30), 200, 5, 2, 6,
                        java.time.Duration.ofSeconds(5), java.time.Duration.ofMinutes(10),
                        java.time.Duration.ofHours(1), java.time.Duration.ofHours(24)),
                new AppProperties.Secrets(null, "local-dev", null, null, java.util.List.of()),
                new AppProperties.Demo(false, "demo", "Demo", java.util.List.of(), false),
                new AppProperties.AbuseControls(10, 20, 5, 30, 5, 10, 5, java.time.Duration.ofHours(1)),
                new AppProperties.ForwardedHeaders(java.util.List.of()),
                new AppProperties.PlatformAdmin(java.util.List.of()),
                new AppProperties.Diagnostics(false));
    }

    @Test
    void doesNothingWhenRealAccountAccessIsNotAuthorized() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);

        assertThatCode(() -> new SingleInstanceGuard(properties(false), dataSource)).doesNotThrowAnyException();

        verify(dataSource, never()).getConnection();
    }

    @Test
    void acquiresTheLockAndSucceedsWhenNoOtherInstanceHoldsIt() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        PreparedStatement lockStatement = Mockito.mock(PreparedStatement.class);
        ResultSet lockResult = Mockito.mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(lockStatement);
        when(lockStatement.executeQuery()).thenReturn(lockResult);
        when(lockResult.next()).thenReturn(true);
        when(lockResult.getBoolean(1)).thenReturn(true);

        assertThatCode(() -> new SingleInstanceGuard(properties(true), dataSource)).doesNotThrowAnyException();

        verify(lockStatement).setLong(1, SingleInstanceGuard.LOCK_KEY);
        verify(connection, never()).close();
    }

    @Test
    void refusesToStartAndClosesTheConnectionWhenAnotherInstanceAlreadyHoldsTheLock() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        PreparedStatement lockStatement = Mockito.mock(PreparedStatement.class);
        ResultSet lockResult = Mockito.mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(lockStatement);
        when(lockStatement.executeQuery()).thenReturn(lockResult);
        when(lockResult.next()).thenReturn(true);
        when(lockResult.getBoolean(1)).thenReturn(false);

        assertThatThrownBy(() -> new SingleInstanceGuard(properties(true), dataSource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another instance already holds");

        verify(connection).close();
    }

    @Test
    void releaseExplicitlyUnlocksBeforeClosingTheHeldConnection() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        PreparedStatement lockStatement = Mockito.mock(PreparedStatement.class);
        ResultSet lockResult = Mockito.mock(ResultSet.class);
        PreparedStatement unlockStatement = Mockito.mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(lockStatement);
        when(lockStatement.executeQuery()).thenReturn(lockResult);
        when(lockResult.next()).thenReturn(true);
        when(lockResult.getBoolean(1)).thenReturn(true);
        when(connection.prepareStatement("SELECT pg_advisory_unlock(?)")).thenReturn(unlockStatement);

        SingleInstanceGuard guard = new SingleInstanceGuard(properties(true), dataSource);
        guard.release();

        verify(unlockStatement).setLong(1, SingleInstanceGuard.LOCK_KEY);
        verify(unlockStatement).execute();
        verify(connection).close();
    }

    @Test
    void releaseIsANoOpWhenTheLockWasNeverAcquired() throws SQLException {
        DataSource dataSource = Mockito.mock(DataSource.class);

        SingleInstanceGuard guard = new SingleInstanceGuard(properties(false), dataSource);
        assertThatCode(guard::release).doesNotThrowAnyException();
    }
}
