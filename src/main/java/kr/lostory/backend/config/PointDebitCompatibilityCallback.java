package kr.lostory.backend.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

@Component
public class PointDebitCompatibilityCallback extends BaseCallback {

	@Override
	public boolean supports(Event event, Context context) {
		return event == Event.BEFORE_MIGRATE;
	}

	@Override
	public void handle(Event event, Context context) {
		Connection connection = context.getConnection();
		try {
			if (!v28IsPending(connection)) {
				return;
			}
			try (Statement statement = connection.createStatement()) {
				statement.execute("""
					CREATE TABLE IF NOT EXISTS point_ledger_v28_debit_compatibility (
						ledger_id BIGINT PRIMARY KEY,
						original_amount INTEGER NOT NULL CHECK (original_amount < 0 AND original_amount <> -1)
					)
					""");
				statement.executeUpdate("""
					INSERT INTO point_ledger_v28_debit_compatibility (ledger_id, original_amount)
					SELECT id, amount
					FROM point_ledger
					WHERE entry_type = 'CANDIDATE_ACCESS_DEBIT' AND amount < 0 AND amount <> -1
					ON CONFLICT (ledger_id) DO NOTHING
					""");
				statement.executeUpdate("""
					UPDATE point_ledger ledger
					SET amount = -1
					FROM point_ledger_v28_debit_compatibility compatibility
					WHERE ledger.id = compatibility.ledger_id
						AND ledger.entry_type = 'CANDIDATE_ACCESS_DEBIT'
						AND ledger.amount <> -1
					""");
			}
		} catch (SQLException exception) {
			throw new FlywayException("failed to stage V27 candidate debit compatibility", exception);
		}
	}

	private boolean v28IsPending(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
					SELECT to_regclass('public.point_ledger') IS NOT NULL
						AND NOT EXISTS (
							SELECT 1 FROM flyway_schema_history WHERE version = '28' AND success
						)
					""")) {
			result.next();
			return result.getBoolean(1);
		}
	}
}
