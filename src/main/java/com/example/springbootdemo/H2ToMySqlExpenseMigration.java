package com.example.springbootdemo;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("mysql")
public class H2ToMySqlExpenseMigration implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(H2ToMySqlExpenseMigration.class);
    private static final String MIGRATION_NAME = "h2-expenses-to-mysql-v1";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final String h2Url;

    public H2ToMySqlExpenseMigration(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            @Value("${app.data.migration.h2-url:jdbc:h2:file:./data/springbootdemo}") String h2Url) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.h2Url = h2Url;
    }

    @Override
    public void run(ApplicationArguments args) throws SQLException {
        Integer alreadyMigrated = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_migrations WHERE name = ?",
                Integer.class,
                MIGRATION_NAME);
        if (alreadyMigrated != null && alreadyMigrated > 0) {
            return;
        }

        List<ExpenseRecord> expenses = readLocalExpenses();
        Integer existingExpenses = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM expenses",
                Integer.class);
        if (existingExpenses != null && existingExpenses > 0) {
            throw new IllegalStateException(
                    "MySQL already contains expenses; refusing to import local H2 expenses to avoid mixing or duplicating records.");
        }

        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.batchUpdate(
                    """
                            INSERT INTO expenses
                                (id, owner_username, amount, category, spent_on, note, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                    expenses,
                    500,
                    (statement, expense) -> {
                        statement.setLong(1, expense.id());
                        statement.setString(2, expense.ownerUsername());
                        statement.setBigDecimal(3, expense.amount());
                        statement.setString(4, expense.category());
                        statement.setObject(5, expense.spentOn());
                        statement.setString(6, expense.note());
                        statement.setTimestamp(7, expense.createdAt());
                    });
            jdbcTemplate.update(
                    "INSERT INTO app_migrations (name) VALUES (?)",
                    MIGRATION_NAME);
        });
        logger.info("Imported {} expense records from the local H2 database into MySQL.", expenses.size());
    }

    private List<ExpenseRecord> readLocalExpenses() throws SQLException {
        List<ExpenseRecord> expenses = new ArrayList<>();
        try (var connection = DriverManager.getConnection(h2Url, "sa", "");
                var statement = connection.prepareStatement("""
                        SELECT id, owner_username, amount, category, spent_on, note, created_at
                        FROM expenses
                        ORDER BY id
                        """);
                var resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                expenses.add(new ExpenseRecord(
                        resultSet.getLong("id"),
                        resultSet.getString("owner_username"),
                        resultSet.getBigDecimal("amount"),
                        resultSet.getString("category"),
                        resultSet.getDate("spent_on").toLocalDate(),
                        resultSet.getString("note"),
                        resultSet.getTimestamp("created_at")));
            }
        } catch (SQLException exception) {
            throw new SQLException(
                    "Could not read existing expenses from the local H2 database at " + h2Url,
                    exception);
        }
        return expenses;
    }

    private record ExpenseRecord(
            long id,
            String ownerUsername,
            BigDecimal amount,
            String category,
            LocalDate spentOn,
            String note,
            java.sql.Timestamp createdAt) {
    }
}
