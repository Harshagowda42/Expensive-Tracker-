package com.example.springbootdemo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ConfiguredAccountInitializer implements ApplicationRunner {

    private final UserAccountService userAccountService;
    private final String username;
    private final String password;

    public ConfiguredAccountInitializer(
            UserAccountService userAccountService,
            @Value("${app.auth.username:}") String username,
            @Value("${app.auth.password:}") String password) {
        this.userAccountService = userAccountService;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        userAccountService.createConfiguredAccount(username, password);
    }
}
