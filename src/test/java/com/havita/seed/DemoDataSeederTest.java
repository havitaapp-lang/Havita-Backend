package com.havita.seed;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.havita.modules.featureflag.domain.FeatureFlag;
import com.havita.modules.featureflag.infrastructure.FeatureFlagRepository;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.subscription.domain.SaaSPlan;
import com.havita.modules.subscription.infrastructure.SaaSPlanRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DemoDataSeederTest {

    private static final UUID SAVED_USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Mock
    private UserRepository userRepository;

    @Mock
    private MembershipRepository membershipRepository;

    @Mock
    private FeatureFlagRepository featureFlagRepository;

    @Mock
    private SaaSPlanRepository saasPlanRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private DemoDataSeeder seeder;

    private ListAppender<ILoggingEvent> listAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        lenient().when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User savedUser = invocation.getArgument(0);
            savedUser.setId(SAVED_USER_ID);
            return savedUser;
        });
        lenient().when(passwordEncoder.encode(any(CharSequence.class)))
                .thenAnswer(invocation -> "ENC::" + invocation.getArgument(0, CharSequence.class));

        logger = (Logger) LoggerFactory.getLogger(DemoDataSeeder.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        if (logger != null && listAppender != null) {
            logger.detachAppender(listAppender);
            listAppender.stop();
        }

        for (ILoggingEvent event : listAppender.list) {
            String msg = event.getFormattedMessage();
            if (msg != null) {
                assertNoSensitiveData(msg);
            }
            if (event.getThrowableProxy() != null && event.getThrowableProxy().getMessage() != null) {
                assertNoSensitiveData(event.getThrowableProxy().getMessage());
            }
        }
    }

    private void assertNoSensitiveData(String text) {
        assertThat(text).doesNotContain("bootstrap.admin@example.test");
        assertThat(text).doesNotContain("Synthetic-Pass16");
        assertThat(text).doesNotContain("Synthetic11");
        assertThat(text).doesNotContain("Synthetic-12");
        assertThat(text).doesNotContain("ENC::");
        assertThat(text).doesNotContain("HASH-ORIGINAL");
    }

    @Test
    void test01_shouldNotCreateSuperAdminWhenBothCredentialsAreNull() {
        ReflectionTestUtils.setField(seeder, "bootstrapSuperAdminEmail", null);
        ReflectionTestUtils.setField(seeder, "bootstrapSuperAdminPassword", null);

        seeder.run();

        verifyNoInteractions(passwordEncoder);
        verify(userRepository, never()).findByEmailIgnoreCase(any());
        verify(userRepository, never()).save(any(User.class));
        verify(membershipRepository, never()).findByUserId(any());
        verify(membershipRepository, never()).save(any(Membership.class));
    }

    @Test
    void test02_shouldNotCreateSuperAdminWhenEmailIsEmpty() {
        ReflectionTestUtils.setField(seeder, "bootstrapSuperAdminEmail", "");
        ReflectionTestUtils.setField(seeder, "bootstrapSuperAdminPassword", "Synthetic-Pass16");

        seeder.run();

        verifyNoInteractions(passwordEncoder);
        verify(userRepository, never()).findByEmailIgnoreCase(any());
        verify(userRepository, never()).save(any(User.class));
        verify(membershipRepository, never()).findByUserId(any());
        verify(membershipRepository, never()).save(any(Membership.class));
    }

    @Test
    void test03_shouldNotCreateSuperAdminWhenPasswordIsEmpty() {
        ReflectionTestUtils.setField(seeder, "bootstrapSuperAdminEmail", "bootstrap.admin@example.test");
        ReflectionTestUtils.setField(seeder, "bootstrapSuperAdminPassword", "");

        seeder.run();

        verifyNoInteractions(passwordEncoder);
        verify(userRepository, never()).findByEmailIgnoreCase(any());
        verify(userRepository, never()).save(any(User.class));
        verify(membershipRepository, never()).findByUserId(any());
        verify(membershipRepository, never()).save(any(Membership.class));
    }

    @Test
    void test04_shouldNotCreateSuperAdminWhenEmailIsWhitespaceOnly() {
        ReflectionTestUtils.setField(seeder, "bootstrapSuperAdminEmail", "   ");
        ReflectionTestUtils.setField(seeder, "bootstrapSuperAdminPassword", "Synthetic-Pass16");

        seeder.run();

        verifyNoInteractions(passwordEncoder);
        verify(userRepository, never()).findByEmailIgnoreCase(any());
        verify(userRepository, never()).save(any(User.class));
        verify(membershipRepository, never()).findByUserId(any());
        verify(membershipRepository, never()).save(any(Membership.class));
    }

    @Test
    void test05_shouldNotCreateSuperAdminWhenPasswordIsWhitespaceOnly() {
        ReflectionTestUtils.setField(seeder, "bootstrapSuperAdminEmail", "bootstrap.admin@example.test");
        ReflectionTestUtils.setField(seeder, "bootstrapSuperAdminPassword", "              "); // 14 spaces

        seeder.run();

        verifyNoInteractions(passwordEncoder);
        verify(userRepository, never()).findByEmailIgnoreCase(any());
        verify(userRepository, never()).save(any(User.class));
        verify(membershipRepository, never()).findByUserId(any());
        verify(membershipRepository, never()).save(any(Membership.class));
    }

    @Test
    void test06_shouldNotCreateSuperAdminWhenPasswordIsLessThan12Chars() {
        ReflectionTestUtils.setField(seeder, "bootstrapSuperAdminEmail", "bootstrap.admin@example.test");
        ReflectionTestUtils.setField(seeder, "bootstrapSuperAdminPassword", "Synthetic11");

        User existingUser = User.builder()
                .email("bootstrap.admin@example.test")
                .build();
        existingUser.setId(UUID.randomUUID());
        lenient().when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(existingUser));

        seeder.run();

        verifyNoInteractions(passwordEncoder);
        verify(userRepository, never()).findByEmailIgnoreCase(any());
        verify(userRepository, never()).save(any(User.class));
        verify(membershipRepository, never()).findByUserId(any());
        verify(membershipRepository, never()).save(any(Membership.class));
        assertThat(listAppender.list).anyMatch(event -> event.getLevel() == Level.WARN);
    }

    @Test
    void test07_shouldCreateSuperAdminWhenPasswordIsExactly12Chars() {
        ReflectionTestUtils.setField(seeder, "bootstrapSuperAdminEmail", "bootstrap.admin@example.test");
        ReflectionTestUtils.setField(seeder, "bootstrapSuperAdminPassword", "Synthetic-12");

        when(userRepository.findByEmailIgnoreCase("bootstrap.admin@example.test")).thenReturn(Optional.empty());
        when(membershipRepository.findByUserId(SAVED_USER_ID)).thenReturn(Collections.emptyList());

        seeder.run();

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("bootstrap.admin@example.test");
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("ENC::Synthetic-12");
        verify(membershipRepository).save(any(Membership.class));
    }

    @Test
    void test08_shouldCreateSuperAdminWithValidCredentialsWhenUserDoesNotExist() {
        ReflectionTestUtils.setField(seeder, "bootstrapSuperAdminEmail", "bootstrap.admin@example.test");
        ReflectionTestUtils.setField(seeder, "bootstrapSuperAdminPassword", "Synthetic-Pass16");

        when(userRepository.findByEmailIgnoreCase("bootstrap.admin@example.test")).thenReturn(Optional.empty());
        when(membershipRepository.findByUserId(SAVED_USER_ID)).thenReturn(Collections.emptyList());

        seeder.run();

        verify(passwordEncoder, times(1)).encode("Synthetic-Pass16");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getEmail()).isEqualTo("bootstrap.admin@example.test");
        assertThat(savedUser.getPasswordHash()).isEqualTo("ENC::Synthetic-Pass16");

        ArgumentCaptor<Membership> membershipCaptor = ArgumentCaptor.forClass(Membership.class);
        verify(membershipRepository).save(membershipCaptor.capture());
        Membership savedMembership = membershipCaptor.getValue();
        assertThat(savedMembership.getRole()).isEqualTo(RoleType.SUPER_ADMIN);
        assertThat(savedMembership.getTenantId()).isNull();
        assertThat(savedMembership.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(savedMembership.getUser()).isEqualTo(savedUser);
    }

    @Test
    void test09_shouldNotModifyExistingSuperAdminWithMemberships() {
        UUID existingUserId = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
        User existingUser = User.builder()
                .email("bootstrap.admin@example.test")
                .passwordHash("HASH-ORIGINAL")
                .build();
        existingUser.setId(existingUserId);
        when(userRepository.findByEmailIgnoreCase("bootstrap.admin@example.test")).thenReturn(Optional.of(existingUser));

        Membership existingMembership = Membership.builder()
                .user(existingUser)
                .role(RoleType.SUPER_ADMIN)
                .status(MembershipStatus.ACTIVE)
                .build();
        when(membershipRepository.findByUserId(existingUserId)).thenReturn(List.of(existingMembership));

        ReflectionTestUtils.setField(seeder, "bootstrapSuperAdminEmail", "bootstrap.admin@example.test");
        ReflectionTestUtils.setField(seeder, "bootstrapSuperAdminPassword", "Synthetic-Pass16");

        seeder.run();

        verify(userRepository).findByEmailIgnoreCase("bootstrap.admin@example.test");
        verify(membershipRepository).findByUserId(existingUserId);
        verifyNoInteractions(passwordEncoder);
        verify(userRepository, never()).save(any(User.class));
        verify(membershipRepository, never()).save(any(Membership.class));
        assertThat(existingUser.getPasswordHash()).isEqualTo("HASH-ORIGINAL");
    }

    @Test
    void test10_shouldAssignSuperAdminMembershipToExistingUserWithoutMemberships() {
        UUID existingUserId = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        User existingUser = User.builder()
                .email("bootstrap.admin@example.test")
                .passwordHash("HASH-ORIGINAL")
                .build();
        existingUser.setId(existingUserId);
        when(userRepository.findByEmailIgnoreCase("bootstrap.admin@example.test")).thenReturn(Optional.of(existingUser));
        when(membershipRepository.findByUserId(existingUserId)).thenReturn(Collections.emptyList());

        ReflectionTestUtils.setField(seeder, "bootstrapSuperAdminEmail", "bootstrap.admin@example.test");
        ReflectionTestUtils.setField(seeder, "bootstrapSuperAdminPassword", "Synthetic-Pass16");

        seeder.run();

        verifyNoInteractions(passwordEncoder);
        verify(userRepository, never()).save(any(User.class));
        assertThat(existingUser.getPasswordHash()).isEqualTo("HASH-ORIGINAL");

        ArgumentCaptor<Membership> membershipCaptor = ArgumentCaptor.forClass(Membership.class);
        verify(membershipRepository, times(1)).save(membershipCaptor.capture());
        assertThat(membershipCaptor.getValue().getRole()).isEqualTo(RoleType.SUPER_ADMIN);
        assertThat(membershipCaptor.getValue().getTenantId()).isNull();
        assertThat(membershipCaptor.getValue().getUser()).isEqualTo(existingUser);
    }

    @Test
    void test11_shouldSeedFeatureFlagsAndPlansWhenRepositoriesEmpty() {
        when(featureFlagRepository.count()).thenReturn(0L);
        when(saasPlanRepository.count()).thenReturn(0L);

        seeder.run();

        verify(featureFlagRepository, atLeastOnce()).save(any(FeatureFlag.class));
        verify(saasPlanRepository, atLeastOnce()).save(any(SaaSPlan.class));
    }

    @Test
    void test12_shouldNotSeedFeatureFlagsAndPlansWhenRepositoriesHaveData() {
        when(featureFlagRepository.count()).thenReturn(1L);
        when(saasPlanRepository.count()).thenReturn(1L);

        seeder.run();

        verify(featureFlagRepository, never()).save(any(FeatureFlag.class));
        verify(saasPlanRepository, never()).save(any(SaaSPlan.class));
    }

    @Test
    void test13_shouldResolveBootstrapCredentialsFromEnvironmentProperties() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> map = new HashMap<>();
        map.put("HAVITA_BOOTSTRAP_SUPER_ADMIN_EMAIL", "bootstrap.admin@example.test");
        map.put("HAVITA_BOOTSTRAP_SUPER_ADMIN_PASSWORD", "Synthetic-Pass16");
        env.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, map)
        );
        ConfigurationPropertySources.attach(env);

        Field emailField = DemoDataSeeder.class.getDeclaredField("bootstrapSuperAdminEmail");
        Field passwordField = DemoDataSeeder.class.getDeclaredField("bootstrapSuperAdminPassword");

        Value emailValue = emailField.getAnnotation(Value.class);
        Value passwordValue = passwordField.getAnnotation(Value.class);

        assertThat(emailValue).isNotNull();
        assertThat(passwordValue).isNotNull();

        String resolvedEmail = env.resolvePlaceholders(emailValue.value());
        String resolvedPassword = env.resolvePlaceholders(passwordValue.value());

        assertThat(resolvedEmail).isEqualTo("bootstrap.admin@example.test");
        assertThat(resolvedPassword).isEqualTo("Synthetic-Pass16");

        StandardEnvironment emptyEnv = new StandardEnvironment();
        emptyEnv.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Collections.emptyMap())
        );
        ConfigurationPropertySources.attach(emptyEnv);

        assertThat(emptyEnv.resolvePlaceholders(emailValue.value())).isEmpty();
        assertThat(emptyEnv.resolvePlaceholders(passwordValue.value())).isEmpty();
    }
}
