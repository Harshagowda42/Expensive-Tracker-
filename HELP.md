# Getting Started

## Login API

The application supports separate, private accounts. Visitors can create an account
from the sign-in page; usernames must be 3–32 letters, numbers, dots, underscores,
or hyphens, and passwords must be at least 10 characters. Passwords are BCrypt-hashed
in the database. Expenses are visible only to the signed-in account.

`APP_AUTH_USERNAME` and `APP_AUTH_PASSWORD` optionally create an initial account on
startup; leave both unset to use open signup only. When both are configured, the
account is created only if it does not already exist.
After signing in, the dashboard lets you add expenses by amount, category, date,
and a required description of what you spent the money on. It shows this month's total
and expenses, lets you choose another month to view its expenses, and lets you delete
entries individually or clear all your expenses at once.
The dashboard asks for confirmation before clearing; this action cannot be undone.
Expenses are saved in a local H2 database under `data/` and remain available across months
and app restarts. Past dates are allowed; future dates are not. Each signed-in username only
sees and can delete their own expense entries.

```powershell
$env:APP_AUTH_USERNAME = "harsha"
$env:APP_AUTH_PASSWORD = "choose-a-strong-password"
$env:SERVER_PORT = "8081"
.\mvnw.cmd spring-boot:run
```

Port `8081` is shown in this example because `8080` may already be in use.
Use `.\mvnw.cmd compile` (or press `Ctrl+Shift+B` in VS Code) to compile with the
project's Maven Wrapper; a separate Maven installation is not required.

## Use local MySQL

The application can use the running local MySQL Server instead of H2. Create the
database once in MySQL:

```sql
CREATE DATABASE expense_tracker;
```

In PowerShell, set the same login credentials you use for MySQL (the password is
only kept in this terminal session), then start the app with the MySQL profile:

```powershell
$env:APP_AUTH_USERNAME = "harsha"
$env:APP_AUTH_PASSWORD = "choose-a-strong-password"
$env:MYSQL_USER = "root"
$env:MYSQL_PASSWORD = "your-mysql-password"
$env:SPRING_PROFILES_ACTIVE = "mysql"
.\mvnw.cmd spring-boot:run
```

If your MySQL user has no password, set `$env:MYSQL_PASSWORD = ""`. On its first
MySQL startup, the app copies the existing local H2 expenses into an empty MySQL
database once. It does not delete the H2 source. If MySQL already has expenses,
startup stops rather than risk mixing records. Keep using the same MySQL database
to retain entries between app restarts.

## Deploy to Render

The repository includes a Render Blueprint (`render.yaml`) and Docker build.
To create a public URL:

1. Push this project to a GitHub repository you control.
2. In Render, choose **New → Blueprint**, connect that repository, and select `render.yaml`.
3. Enter `APP_AUTH_USERNAME` and a strong, unique `APP_AUTH_PASSWORD` when Render prompts for them.
4. Apply the Blueprint and wait for the health check to pass. Open the generated `onrender.com` URL to use the app.

The included Blueprint uses Render's free web service and free PostgreSQL database to avoid
automatic charges. Free instances may sleep, and free database storage is temporary and may
expire; do not use this setup for important expense records. Upgrade the Render database to
a paid persistent plan before relying on it for long-term storage. Never put passwords in
`render.yaml` or commit them to GitHub.

The API uses a server-side session cookie and CSRF protection:

1. `GET /api/csrf` to receive a CSRF token. Keep the `XSRF-TOKEN` cookie and token from the JSON response.
2. `POST /api/login` with `{"username":"harsha","password":"..."}`, the cookie, and an `X-XSRF-TOKEN` header containing the token.
3. Use the returned session cookie for authenticated requests such as `GET /api/me`.
4. `GET /api/expenses` to list your expenses.
5. `POST /api/expenses` with `{"amount":125.50,"category":"Food","date":"2026-10-23","note":"Lunch"}` to add one. The note must describe what the expense was for, and the date cannot be in the future.
6. `DELETE /api/expenses/{id}` to remove one of your own expenses.
7. `DELETE /api/expenses` to clear all expenses belonging to the signed-in user.
8. `POST /api/logout` with the session cookie, CSRF cookie, and `X-XSRF-TOKEN` header to log out.

All expense changes require the session cookie and `X-XSRF-TOKEN` header. Login returns `401`
for invalid credentials; protected endpoints return `401` when unauthenticated.

### Reference Documentation
For further reference, please consider the following sections:

* [Official Apache Maven documentation](https://maven.apache.org/guides/index.html)
* [Spring Boot Maven Plugin Reference Guide](https://docs.spring.io/spring-boot/4.1.2/maven-plugin)
* [Create an OCI image](https://docs.spring.io/spring-boot/4.1.2/maven-plugin/build-image.html)

### Maven Parent overrides

Due to Maven's design, elements are inherited from the parent POM to the project POM.
While most of the inheritance is fine, it also inherits unwanted elements like `<license>` and `<developers>` from the parent.
To prevent this, the project POM contains empty overrides for these elements.
If you manually switch to a different parent and actually want the inheritance, you need to remove those overrides.
