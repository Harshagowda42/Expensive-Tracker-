package com.example.springbootdemo;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;

@AutoConfigureMockMvc
@SpringBootTest(properties = {
		"app.auth.username=test-user",
		"app.auth.password=test-password",
		"spring.datasource.url=jdbc:h2:mem:springbootdemo-test;DB_CLOSE_DELAY=-1"
})
class SpringbootdemoApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@BeforeEach
	void clearExpenses() {
		jdbcTemplate.update("DELETE FROM expenses");
	}

	@Test
	void loginPageIsAvailableWithoutAuthentication() throws Exception {
		mockMvc.perform(get("/"))
				.andExpect(status().isOk())
				.andExpect(forwardedUrl("index.html"));

		mockMvc.perform(get("/index.html"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith("text/html"))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Welcome back")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Create an account")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Your sign-in expired")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Hello, ${username}!")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Add an expense")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Monthly expenses")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"month-filter\"")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"clear-expenses-button\"")));
	}

	@Test
	void registrationCreatesPrivateAccountWithHashedPasswordAndStartsSession() throws Exception {
		String username = "member-" + UUID.randomUUID().toString().substring(0, 8);
		String password = "member-password-123";

		MvcResult registration = mockMvc.perform(post("/api/register")
						.with(csrf())
						.contentType("application/json")
						.content("""
								{"username":"%s","password":"%s"}
								""".formatted(username.toUpperCase(), password)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.username").value(username))
				.andReturn();

		String storedHash = jdbcTemplate.queryForObject(
				"SELECT password_hash FROM app_users WHERE username = ?",
				String.class,
				username);
		org.junit.jupiter.api.Assertions.assertNotEquals(password, storedHash);
		org.junit.jupiter.api.Assertions.assertTrue(passwordEncoder.matches(password, storedHash));

		MockHttpSession session = (MockHttpSession) registration.getRequest().getSession(false);
		mockMvc.perform(get("/api/me").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value(username));

		mockMvc.perform(post("/api/expenses")
						.session(session)
						.with(csrf())
						.contentType("application/json")
						.content("""
								{"amount":45.00,"category":"Food","date":"%s","note":"Member lunch"}
								""".formatted(LocalDate.now())))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/api/expenses").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].note").value("Member lunch"));
		mockMvc.perform(get("/api/expenses").with(user("another-member")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void registrationRejectsWeakPasswordsAndDuplicateUsernames() throws Exception {
		mockMvc.perform(post("/api/register")
						.with(csrf())
						.contentType("application/json")
						.content("""
								{"username":"short-password","password":"weak"}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("10 characters")));

		String username = "duplicate-" + UUID.randomUUID().toString().substring(0, 8);
		String registration = """
				{"username":"%s","password":"valid-password-123"}
				""".formatted(username);
		mockMvc.perform(post("/api/register").with(csrf()).contentType("application/json").content(registration))
				.andExpect(status().isCreated());
		mockMvc.perform(post("/api/register").with(csrf()).contentType("application/json").content(registration))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("That username is already taken."));
	}

	@Test
	void deploymentHealthCheckIsPublic() throws Exception {
		mockMvc.perform(get("/api/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ok"));
	}

	@Test
	void clearAllDeletesOnlyTheCurrentUsersExpenses() throws Exception {
		LocalDate today = LocalDate.now();
		jdbcTemplate.update(
				"""
						INSERT INTO expenses (owner_username, amount, category, spent_on, note)
						VALUES (?, ?, ?, ?, ?), (?, ?, ?, ?, ?)
						""",
				"test-user",
				new java.math.BigDecimal("10.00"),
				"Food",
				today,
				"Lunch",
				"another-user",
				new java.math.BigDecimal("20.00"),
				"Bills",
				today,
				"Internet");

		mockMvc.perform(delete("/api/expenses")
						.with(user("test-user"))
						.with(csrf()))
				.andExpect(status().isNoContent());

		Integer remainingTestUserExpenses = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM expenses WHERE owner_username = ?",
				Integer.class,
				"test-user");
		Integer remainingOtherUserExpenses = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM expenses WHERE owner_username = ?",
				Integer.class,
				"another-user");
		org.junit.jupiter.api.Assertions.assertEquals(0, remainingTestUserExpenses);
		org.junit.jupiter.api.Assertions.assertEquals(1, remainingOtherUserExpenses);
	}

	@Test
	void expensesAreSavedListedAndDeletedOnlyByTheirOwner() throws Exception {
		String note = "Lunch-" + UUID.randomUUID();
		mockMvc.perform(post("/api/expenses")
						.with(user("test-user"))
						.with(csrf())
						.contentType("application/json")
						.content("""
								{"amount":125.50,"category":"Food","date":"%s","note":"%s"}
								""".formatted(LocalDate.now(), note)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.amount").value(125.5))
				.andExpect(jsonPath("$.category").value("Food"));

		long id = jdbcTemplate.queryForObject(
				"SELECT id FROM expenses WHERE owner_username = ? AND note = ?",
				Long.class,
				"test-user",
				note);

		mockMvc.perform(get("/api/expenses").with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(id))
				.andExpect(jsonPath("$[0].note").value(note));

		mockMvc.perform(get("/api/expenses").with(user("another-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isEmpty());

		mockMvc.perform(delete("/api/expenses/{id}", id)
						.with(user("another-user"))
						.with(csrf()))
				.andExpect(status().isNotFound());

		mockMvc.perform(delete("/api/expenses/{id}", id)
						.with(user("test-user"))
						.with(csrf()))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/expenses").with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void expenseAmountMustBePositiveAndHaveAtMostTwoDecimals() throws Exception {
		mockMvc.perform(post("/api/expenses")
						.with(user("test-user"))
						.with(csrf())
						.contentType("application/json")
						.content("""
								{"amount":0,"category":"Food","date":"2026-10-23","note":""}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").isNotEmpty());

		mockMvc.perform(post("/api/expenses")
						.with(user("test-user"))
						.with(csrf())
						.contentType("application/json")
						.content("""
								{"amount":1.234,"category":"Food","date":"2026-10-23","note":""}
								"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void expenseRequiresDescriptionOfWhatWasPurchased() throws Exception {
		mockMvc.perform(post("/api/expenses")
						.with(user("test-user"))
						.with(csrf())
						.contentType("application/json")
						.content("""
								{"amount":12.50,"category":"Food","date":"%s","note":" "}
								""".formatted(LocalDate.now())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Describe what you spent this on."));

		mockMvc.perform(post("/api/expenses")
						.with(user("test-user"))
						.with(csrf())
						.contentType("application/json")
						.content("""
								{"amount":12.50,"category":"Food","date":"%s"}
								""".formatted(LocalDate.now())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Describe what you spent this on."));
	}

	@Test
	void listingExpensesKeepsExpensesFromPreviousMonthsForMonthSelection() throws Exception {
		LocalDate today = LocalDate.now();
		LocalDate previousMonth = today.minusMonths(1).withDayOfMonth(1);
		jdbcTemplate.update(
				"""
						INSERT INTO expenses (owner_username, amount, category, spent_on, note)
						VALUES (?, ?, ?, ?, ?)
						""",
				"test-user",
				new java.math.BigDecimal("10.00"),
				"Food",
				previousMonth,
				"Old expense");

		mockMvc.perform(get("/api/expenses").with(user("test-user")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].note").value("Old expense"));

		Integer remainingExpenses = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM expenses WHERE spent_on < ?",
				Integer.class,
				today.withDayOfMonth(1));
		org.junit.jupiter.api.Assertions.assertEquals(1, remainingExpenses);
	}

	@Test
	void newExpensesCanBeBackdatedButNotSetInTheFuture() throws Exception {
		LocalDate yesterday = LocalDate.now().minusMonths(1);
		mockMvc.perform(post("/api/expenses")
						.with(user("test-user"))
						.with(csrf())
						.contentType("application/json")
						.content("""
								{"amount":12.50,"category":"Food","date":"%s","note":"Old lunch"}
								""".formatted(yesterday)))
				.andExpect(status().isCreated());

		LocalDate tomorrow = LocalDate.now().plusDays(1);
		mockMvc.perform(post("/api/expenses")
						.with(user("test-user"))
						.with(csrf())
						.contentType("application/json")
						.content("""
								{"amount":12.50,"category":"Food","date":"%s","note":"Lunch"}
								""".formatted(tomorrow)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value(
						"Choose a valid date that is not in the future."));
	}

	@Test
	void expenseEndpointsRequireAuthenticationAndCsrf() throws Exception {
		mockMvc.perform(get("/api/expenses"))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(delete("/api/expenses"))
				.andExpect(status().isForbidden());

		mockMvc.perform(delete("/api/expenses")
						.with(csrf()))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(delete("/api/expenses")
						.with(user("test-user")))
				.andExpect(status().isForbidden());

		mockMvc.perform(post("/api/expenses")
						.with(user("test-user"))
						.contentType("application/json")
						.content("""
								{"amount":1,"category":"Food","date":"2026-10-23","note":""}
								"""))
				.andExpect(status().isForbidden());
	}

	@Test
	void loginAllowsAccessAndLogoutEndsSession() throws Exception {
		mockMvc.perform(get("/api/csrf"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").isNotEmpty());

		MvcResult loginResult = mockMvc.perform(post("/api/login")
						.with(csrf())
						.contentType("application/json")
						.content("""
								{"username":"test-user","password":"test-password"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("test-user"))
				.andReturn();

		MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
		mockMvc.perform(get("/api/me").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("test-user"));

		mockMvc.perform(post("/api/logout")
						.session(session)
						.with(csrf()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("Logged out"));

		mockMvc.perform(get("/api/me").session(session))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void loginRejectsInvalidCredentials() throws Exception {
		mockMvc.perform(post("/api/login")
						.with(csrf())
						.contentType("application/json")
						.content("""
								{"username":"test-user","password":"wrong-password"}
								"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error").value("Invalid username or password"));
	}

	@Test
	void loginRequiresCsrfToken() throws Exception {
		mockMvc.perform(post("/api/login")
						.contentType("application/json")
						.content("""
								{"username":"test-user","password":"test-password"}
								"""))
				.andExpect(status().isForbidden());
	}

	@Test
	void loginRequiresUsernameAndPassword() throws Exception {
		mockMvc.perform(post("/api/login")
						.with(csrf())
						.contentType("application/json")
						.content("""
								{"username":"","password":""}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Username and password are required"));
	}

}
