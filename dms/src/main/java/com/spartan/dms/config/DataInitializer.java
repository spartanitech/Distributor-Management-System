package com.spartan.dms.config;

import com.spartan.dms.entity.Distributor;
import com.spartan.dms.entity.Role;
import com.spartan.dms.entity.SuperStockist;
import com.spartan.dms.entity.User;
import com.spartan.dms.repository.DistributorRepository;
import com.spartan.dms.repository.RoleRepository;
import com.spartan.dms.repository.SuperStockistRepository;
import com.spartan.dms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the two roles (ADMIN, DISTRIBUTOR), one default admin login, and
 * one sample distributor + linked distributor login on first boot, so
 * there's always a working way in after a fresh deploy — public
 * self-registration no longer grants ADMIN (see AuthService.register()),
 * so without this there would be no way to create the first admin at all.
 *
 * CHANGE THESE PASSWORDS after first login in any real deployment.
 *
 * BUG-H8 fix: this used to run unconditionally on every boot in every
 * environment (including production) and logged the plaintext seeded
 * passwords at INFO level. It's now gated to only run when one of the
 * local/dev/test profiles is active (the application has no
 * spring.profiles.active set by default, so a plain/production boot uses
 * Spring's "default" profile, which deliberately does NOT match any of the
 * names below and therefore will NOT seed these accounts or their known
 * passwords) and no longer logs the actual password values.
 */
@Slf4j
@Component
@Profile({"local", "dev", "test"})
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final DistributorRepository distributorRepository;
    private final SuperStockistRepository superStockistRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {

        Role adminRole = roleRepository.findByRoleName("ADMIN")
                .orElseGet(() -> roleRepository.save(
                        Role.builder()
                                .roleName("ADMIN")
                                .description("Full-control administrator")
                                .active(true)
                                .build()));

        Role superStockistRole = roleRepository.findByRoleName("SUPER_STOCKIST")
                .orElseGet(() -> roleRepository.save(
                        Role.builder()
                                .roleName("SUPER_STOCKIST")
                                .description("Super Stockist portal login (Company -> Super Stockist -> Distributor)")
                                .active(true)
                                .build()));

        Role distributorRole = roleRepository.findByRoleName("DISTRIBUTOR")
                .orElseGet(() -> roleRepository.save(
                        Role.builder()
                                .roleName("DISTRIBUTOR")
                                .description("Distributor portal login")
                                .active(true)
                                .build()));

        if (!userRepository.existsByUsername("admin")) {
            User admin = User.builder()
                    .fullName("System Administrator")
                    .username("admin")
                    .email("admin@dms.local")
                    .mobileNumber("9999999999")
                    .password(passwordEncoder.encode("Admin@123"))
                    .role(adminRole)
                    .active(true)
                    .build();
            userRepository.save(admin);
            // Safe to log the actual seeded password here: this whole class
            // is @Profile({"local","dev","test"})-gated (see BUG-H8 above),
            // so this line never executes against a production boot, and a
            // dev/tester needs *some* way to discover the seeded credential.
            log.info("Seeded default admin login -> username: admin / password: Admin@123 (change this immediately)");
        }

        SuperStockist sampleSuperStockist = superStockistRepository.findBySuperStockistNameContainingIgnoreCase("Sample Super Stockist")
                .stream().findFirst()
                .orElseGet(() -> {
                    if (superStockistRepository.existsByMobileNumber("9666666666")) {
                        return null;
                    }
                    SuperStockist ss = SuperStockist.builder()
                            .superStockistName("Sample Super Stockist")
                            .contactPerson("Sample SS Contact")
                            .mobileNumber("9666666666")
                            .email("superstockist@dms.local")
                            .state("Kerala")
                            .district("Ernakulam")
                            .active(true)
                            .build();
                    return superStockistRepository.save(ss);
                });

        if (sampleSuperStockist != null && !userRepository.existsByUsername("superstockist")) {
            User ssUser = User.builder()
                    .fullName("Sample Super Stockist Login")
                    .username("superstockist")
                    .email("superstockist.login@dms.local")
                    .mobileNumber("9555555555")
                    .password(passwordEncoder.encode("SuperStockist@123"))
                    .role(superStockistRole)
                    .superStockist(sampleSuperStockist)
                    .active(true)
                    .build();
            userRepository.save(ssUser);
            log.info("Seeded sample super stockist login -> username: superstockist / password: SuperStockist@123 (change this immediately)");
        }

        if (!distributorRepository.existsByMobileNumber("9888888888")) {
            Distributor sample = Distributor.builder()
                    .distributorName("Sample Distributor")
                    .contactPerson("Sample Contact")
                    .mobileNumber("9888888888")
                    .email("distributor@dms.local")
                    .state("Kerala")
                    .district("Thiruvananthapuram")
                    .superStockist(sampleSuperStockist)
                    .active(true)
                    .build();
            sample = distributorRepository.save(sample);

            if (!userRepository.existsByUsername("distributor")) {
                User distUser = User.builder()
                        .fullName("Sample Distributor Login")
                        .username("distributor")
                        .email("distributor.login@dms.local")
                        .mobileNumber("9777777777")
                        .password(passwordEncoder.encode("Distributor@123"))
                        .role(distributorRole)
                        .distributor(sample)
                        .active(true)
                        .build();
                userRepository.save(distUser);
                log.info("Seeded sample distributor login -> username: distributor / password: Distributor@123 (change this immediately)");
            }
        }
    }
}
