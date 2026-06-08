package com.softropic.skillars.platform.security.repo;


import com.softropic.skillars.infrastructure.validation.PhoneNumber;
import com.softropic.skillars.infrastructure.validation.Provider;
import com.softropic.skillars.config.TestConfig;
import com.softropic.skillars.platform.security.SecurityIT;
import com.softropic.skillars.platform.security.repo.Address;
import com.softropic.skillars.platform.security.repo.User;
import com.softropic.skillars.platform.security.repo.UserRepository;

import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {"ledger.database.spy=true", "enable.test.mail=true"})
@Import(TestConfig.class)
@TestPropertySource(properties = "spring.cloud.compatibility-verifier.enabled=false")
@Sql({SecurityIT.SEC_DATA_SQL_PATH})
class UserRepositoryIT {

    @Autowired
    private UserRepository userRepo;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void findOneById() {
        final User user = Instancio.create(User.class);
        user.setId(null);
        user.setPersistentTokens(Collections.emptySet());
        user.setLangKey("en");
        user.setEmail("me@yahoo.com");
        user.setPassword("sixtysixtysixtysixtysixtysixtysixtysixtysixtysixtysixtysixty");
        user.setLogin("me@yahoo.com");
        user.setDateOfBirth(LocalDate.of(1978, 3, 19));
        user.setPhone(new PhoneNumber("01794443151", Provider.MTN, "DE"));
        final Address address = user.getAddresses().stream().findFirst().orElse(null);
        address.setName("abcdAddress");
        user.setAddresses(Set.of(address));
        user.setAuthorities(Set.of());

        final User savedUser = userRepo.save(user);
        final Optional<User> foundUser = userRepo.findOneById(savedUser.getId());

        assertThat(foundUser).isNotEmpty();
    }
}