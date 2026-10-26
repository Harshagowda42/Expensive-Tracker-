package com.example.springbootdemo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/expenses")
public class ExpenseController {

    private static final RowMapper<Expense> EXPENSE_ROW_MAPPER = (resultSet, rowNumber) -> new Expense(
            resultSet.getLong("id"),
            resultSet.getBigDecimal("amount"),
            resultSet.getString("category"),
            resultSet.getDate("spent_on").toLocalDate(),
            resultSet.getString("note"));

    private final JdbcTemplate jdbcTemplate;

    public ExpenseController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public List<Expense> list(Authentication authentication) {
        return jdbcTemplate.query(
                """
                        SELECT id, amount, category, spent_on, note
                        FROM expenses
                        WHERE owner_username = ?
                        ORDER BY spent_on DESC, id DESC
                        """,
                EXPENSE_ROW_MAPPER,
                authentication.getName());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody ExpenseRequest expense, Authentication authentication) {
        if (expense == null
                || expense.amount() == null
                || expense.amount().signum() <= 0
                || expense.amount().scale() > 2
                || expense.amount().compareTo(new BigDecimal("999999999.99")) > 0) {
            return badRequest("Enter an amount greater than 0 with no more than 2 decimal places.");
        }
        if (expense.category() == null
                || expense.category().isBlank()
                || expense.category().trim().length() > 40) {
            return badRequest("Choose a category with at most 40 characters.");
        }
        if (expense.date() == null || expense.date().isAfter(LocalDate.now())) {
            return badRequest("Choose a valid date that is not in the future.");
        }
        if (expense.note() == null || expense.note().isBlank()) {
            return badRequest("Describe what you spent this on.");
        }
        if (expense.note().trim().length() > 200) {
            return badRequest("The description must be 200 characters or fewer.");
        }

        String note = expense.note().trim();
        String category = expense.category().trim();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(
                    """
                            INSERT INTO expenses (owner_username, amount, category, spent_on, note)
                            VALUES (?, ?, ?, ?, ?)
                            """,
                    new String[] { "ID" });
            statement.setString(1, authentication.getName());
            statement.setBigDecimal(2, expense.amount());
            statement.setString(3, category);
            statement.setObject(4, expense.date());
            statement.setString(5, note);
            return statement;
        }, keyHolder);

        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("The database did not return an ID for the saved expense.");
        }
        Expense saved = jdbcTemplate.queryForObject(
                """
                        SELECT id, amount, category, spent_on, note
                        FROM expenses
                        WHERE id = ? AND owner_username = ?
                        """,
                EXPENSE_ROW_MAPPER,
                id.longValue(),
                authentication.getName());
        if (saved == null) {
            throw new IllegalStateException("The saved expense could not be loaded.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteAll(Authentication authentication) {
        jdbcTemplate.update(
                "DELETE FROM expenses WHERE owner_username = ?",
                authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable long id, Authentication authentication) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM expenses WHERE id = ? AND owner_username = ?",
                id,
                authentication.getName());
        if (deleted == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Expense not found."));
        }
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    public record ExpenseRequest(BigDecimal amount, String category, LocalDate date, String note) {
    }

    public record Expense(long id, BigDecimal amount, String category, LocalDate date, String note) {
    }
}
