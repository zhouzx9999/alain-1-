package com.bigcloud.alain.service;

import com.aliyuncs.CommonResponse;
import com.bigcloud.alain.config.Constants;
import com.bigcloud.alain.domain.Authority;
import com.bigcloud.alain.domain.Org;
import com.bigcloud.alain.domain.Role;
import com.bigcloud.alain.domain.User;
import com.bigcloud.alain.repository.AuthorityRepository;
import com.bigcloud.alain.repository.OrgRepository;
import com.bigcloud.alain.repository.RoleRepository;
import com.bigcloud.alain.repository.UserRepository;
import com.bigcloud.alain.security.AuthoritiesConstants;
import com.bigcloud.alain.security.SecurityUtils;
import com.bigcloud.alain.service.dto.UserDTO;
import com.bigcloud.alain.service.util.HelperUtil;
import com.bigcloud.alain.service.util.RandomUtil;
import com.bigcloud.alain.service.util.SendSmsUtil;
import com.bigcloud.alain.web.rest.errors.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static com.bigcloud.alain.web.rest.EMailResource.EMAIL_CAPTCHA_CACHE;

/**
 * Service class for managing users.
 */
@Service
@Transactional
public class UserService {

    private final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    private final AuthorityRepository authorityRepository;

    private final RoleRepository roleRepository;

    private final OrgRepository orgRepository;

    private final CacheManager cacheManager;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       AuthorityRepository authorityRepository, RoleRepository roleRepository,
                       OrgRepository orgRepository, CacheManager cacheManager) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authorityRepository = authorityRepository;
        this.roleRepository = roleRepository;
        this.orgRepository = orgRepository;
        this.cacheManager = cacheManager;
    }

    public Page<UserDTO> getUserByItemPage(String item, Pageable pageable) {
        return userRepository.getUserByItem(item, pageable).map(UserDTO::new);
    }

    public UserDTO findByLogin(String login) {
        return new UserDTO(userRepository.findByLogin(login));
    }

    public UserDTO findById(Long userId) {
        Optional<User> user = userRepository.findById(userId);
        if (user.isPresent()) {
            return new UserDTO(user.get());
        }
        return new UserDTO();
    }

    public Optional<User> activateRegistration(String key) {
        log.debug("Activating user for activation key {}", key);
        return userRepository.findOneByActivationKey(key)
            .map(user -> {
                // activate given user for the registration key.
                user.setActivated(true);
                user.setActivationKey(null);
                this.clearUserCaches(user);
                log.debug("Activated user: {}", user);
                return user;
            });
    }

    public Optional<User> completePasswordReset(String newPassword, String key) {
        log.debug("Reset user password for reset key {}", key);
        return userRepository.findOneByResetKey(key)
            .filter(user -> user.getResetDate().isAfter(Instant.now().minusSeconds(86400)))
            .map(user -> {
                user.setPassword(passwordEncoder.encode(newPassword));
                user.setResetKey(null);
                user.setResetDate(null);
                this.clearUserCaches(user);
                return user;
            });
    }

    public Optional<User> requestPasswordReset(String mail) {
        return userRepository.findOneByEmailIgnoreCase(mail)
            .filter(User::getActivated)
            .map(user -> {
                user.setResetKey(RandomUtil.generateResetKey());
                user.setResetDate(Instant.now());
                this.clearUserCaches(user);
                return user;
            });
    }

    public User registerUser(UserDTO userDTO, String password) {
        userRepository.findOneByLogin(userDTO.getLogin().toLowerCase()).ifPresent(existingUser -> {
            boolean removed = removeNonActivatedUser(existingUser);
            if (!removed) {
                throw new LoginAlreadyUsedException();
            }
        });
        userRepository.findOneByEmailIgnoreCase(userDTO.getEmail()).ifPresent(existingUser -> {
            boolean removed = removeNonActivatedUser(existingUser);
            if (!removed) {
                throw new EmailAlreadyUsedException();
            }
        });
        User newUser = new User();
        String encryptedPassword = passwordEncoder.encode(password);
        Integer passwordState = HelperUtil.passwordState(password);
        newUser.setPasswordState(passwordState);
        newUser.setLogin(userDTO.getLogin().toLowerCase());
        // new user gets initially a generated password
        newUser.setPassword(encryptedPassword);
        newUser.setFirstName(userDTO.getFirstName());
        newUser.setLastName(userDTO.getLastName());
        newUser.setEmail(userDTO.getEmail().toLowerCase());
        newUser.setTelephone(userDTO.getTelephone());
        newUser.setImageUrl(userDTO.getImageUrl());
        newUser.setLangKey(userDTO.getLangKey());
        newUser.setCreatedDate(userDTO.getCreatedDate());
        // new user is not active
        newUser.setActivated(userDTO.isActivated());
        // new user gets registration key
        newUser.setActivationKey(RandomUtil.generateActivationKey());
//        Set<Authority> authorities = new HashSet<>();
//        authorityRepository.findById(AuthoritiesConstants.USER).ifPresent(authorities::add);
//        newUser.setAuthorities(authorities);
        if (userDTO.getRoles() != null) {
            Set<Role> roles = userDTO.getRoles().stream()
                .map(roleRepository::findRoleById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toSet());
            newUser.setRoles(roles);
        }
        if (userDTO.getOrgs() != null) {
            Set<Org> orgs = userDTO.getOrgs().stream()
                .map(orgRepository::findOrgById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toSet());
            newUser.setOrgs(orgs);
        }
        userRepository.save(newUser);
        this.clearUserCaches(newUser);
        log.debug("Created Information for User: {}", newUser);
        return newUser;
    }
    private boolean removeNonActivatedUser(User existingUser){
        if (existingUser.getActivated()) {
             return false;
        }
        userRepository.delete(existingUser);
        userRepository.flush();
        this.clearUserCaches(existingUser);
        return true;
    }

    public User createUser(UserDTO userDTO) {
        User user = new User();
        user.setLogin(userDTO.getLogin().toLowerCase());
        user.setRealName(userDTO.getRealName());
        user.setNickName(userDTO.getNickName());
        user.setFirstName(userDTO.getFirstName());
        user.setLastName(userDTO.getLastName());
        user.setEmail(userDTO.getEmail().toLowerCase());
        user.setTelephone(userDTO.getTelephone());
        user.setImageUrl(userDTO.getImageUrl());
        user.setIntroduce(userDTO.getIntroduce());
        user.setAddress(userDTO.getAddress());
        if (userDTO.getLangKey() == null) {
            user.setLangKey(Constants.DEFAULT_LANGUAGE); // default language
        } else {
            user.setLangKey(userDTO.getLangKey());
        }
        String encryptedPassword = passwordEncoder.encode(RandomUtil.generatePassword());
        user.setPassword(encryptedPassword);
        Integer passwordState = HelperUtil.passwordState(RandomUtil.generatePassword());
        user.setPasswordState(passwordState);
        user.setResetKey(RandomUtil.generateResetKey());
        user.setResetDate(Instant.now());
        user.setActivated(userDTO.isActivated());
        if (userDTO.getRoles() != null) {
            Set<Role> roles = userDTO.getRoles().stream()
                .map(roleRepository::findRoleById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toSet());
            user.setRoles(roles);
        }
        if (userDTO.getOrgs() != null) {
            Set<Org> orgs = userDTO.getOrgs().stream()
                .map(orgRepository::findOrgById)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toSet());
            user.setOrgs(orgs);
        }
//        if (userDTO.getAuthorities() != null) {
//            Set<Authority> authorities = userDTO.getAuthorities().stream()
//                .map(authorityRepository::findById)
//                .filter(Optional::isPresent)
//                .map(Optional::get)
//                .collect(Collectors.toSet());
//            user.setAuthorities(authorities);
//        }
        userRepository.save(user);
        this.clearUserCaches(user);
        log.debug("Created Information for User: {}", user);
        return user;
    }

    /**
     * Update basic information (first name, last name, email, language) for the current user.
     *
     * @param firstName first name of user
     * @param lastName last name of user
     * @param email email id of user
     * @param langKey language key
     * @param imageUrl image URL of user
     */
    public void updateUser(String firstName, String lastName, String email, String langKey, String imageUrl) {
        SecurityUtils.getCurrentUserLogin()
            .flatMap(userRepository::findOneByLogin)
            .ifPresent(user -> {
                user.setFirstName(firstName);
                user.setLastName(lastName);
                user.setEmail(email.toLowerCase());
                user.setLangKey(langKey);
                user.setImageUrl(imageUrl);
                this.clearUserCaches(user);
                log.debug("Changed Information for User: {}", user);
            });
    }

    /**
     * Update all information for a specific user, and return the modified user.
     *
     * @param userDTO user to update
     * @return updated user
     */
    public Optional<UserDTO> updateUser(UserDTO userDTO) {
        return Optional.of(userRepository
            .findById(userDTO.getId()))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .map(user -> {
                this.clearUserCaches(user);
                user.setLogin(userDTO.getLogin().toLowerCase());
                if (!"888888".equals(userDTO.getPassword())) {
                    String encryptedPassword = passwordEncoder.encode(userDTO.getPassword());
                    user.setPassword(encryptedPassword);
                    Integer passwordState = HelperUtil.passwordState(userDTO.getPassword());
                    user.setPasswordState(passwordState);
                }
                user.setRealName(userDTO.getRealName());
                user.setNickName(userDTO.getNickName());
                user.setFirstName(userDTO.getFirstName());
                user.setLastName(userDTO.getLastName());
                user.setEmail(userDTO.getEmail().toLowerCase());
                user.setTelephone(userDTO.getTelephone());
                user.setImageUrl(userDTO.getImageUrl());
                user.setIntroduce(userDTO.getIntroduce());
                user.setAddress(userDTO.getAddress());
                user.setActivated(userDTO.isActivated());
                user.setLangKey(userDTO.getLangKey());
//                Set<Authority> managedAuthorities = user.getAuthorities();
//                managedAuthorities.clear();
//                userDTO.getAuthorities().stream()
//                    .map(authorityRepository::findById)
//                    .filter(Optional::isPresent)
//                    .map(Optional::get)
//                    .forEach(managedAuthorities::add);
                Set<Role> roles = user.getRoles();
                Set<Org> orgs = user.getOrgs();
                roles.clear();
                orgs.clear();
                userDTO.getRoles().stream()
                    .map(roleRepository::findById)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .forEach(roles::add);
                userDTO.getOrgs().stream()
                    .map(orgRepository::findById)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .forEach(orgs::add);
                this.clearUserCaches(user);
                log.debug("Changed Information for User: {}", user);
                return user;
            })
            .map(UserDTO::new);
    }

    public void deleteUser(String login) {
        userRepository.findOneByLogin(login).ifPresent(user -> {
            userRepository.delete(user);
            this.clearUserCaches(user);
            log.debug("Deleted User: {}", user);
        });
    }

    public void changePassword(String currentClearTextPassword, String newPassword) {
        SecurityUtils.getCurrentUserLogin()
            .flatMap(userRepository::findOneByLogin)
            .ifPresent(user -> {
                String currentEncryptedPassword = user.getPassword();
                if (!passwordEncoder.matches(currentClearTextPassword, currentEncryptedPassword)) {
                    throw new InvalidPasswordException();
                }
                String encryptedPassword = passwordEncoder.encode(newPassword);
                user.setPassword(encryptedPassword);
                Integer passwordState = HelperUtil.passwordState(newPassword);
                user.setPasswordState(passwordState);
                this.clearUserCaches(user);
                log.debug("Changed password for User: {}", user);
            });
    }

    public void changePhone(String newPhone, String captcha) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
        String nowDate = sdf.format(new Date());
        // 调用阿里云短信服务中查看短信发送记录和发送状态方法(未测试)
        CommonResponse response = SendSmsUtil.querySendDetails(newPhone, nowDate, captcha);
//        String data = response.getData();
        // 判断输入的短信验证码与发送的是否一致
        boolean b  = true;
        if (b) {
            SecurityUtils.getCurrentUserLogin()
                .flatMap(userRepository::findOneByLogin)
                .ifPresent(user -> {
                    user.setTelephone(newPhone);
                    this.clearUserCaches(user);
                    log.debug("Changed phone for User: {}", user);
                });
        }
    }

    public void changeEmail(String newEmail, String captcha) {
        // 判断输入的邮箱验证码与缓存中发送的是否一致
        Cache cache = cacheManager.getCache(EMAIL_CAPTCHA_CACHE);
        if (captcha == cache.get("emailCaptcha", String.class) || captcha.equals(cache.get("emailCaptcha", String.class))) {
            SecurityUtils.getCurrentUserLogin()
                .flatMap(userRepository::findOneByLogin)
                .ifPresent(user -> {
                    user.setEmail(newEmail);
                    this.clearUserCaches(user);
                    log.debug("Changed email for User: {}", user);
                });
        }
    }

    public Boolean checkCurrentPassword(String currentPassword) {
        final boolean[] b = new boolean[1];
        SecurityUtils.getCurrentUserLogin()
            .flatMap(userRepository::findOneByLogin)
            .ifPresent(user -> {
                String currentEncryptedPassword = user.getPassword();
                if (!passwordEncoder.matches(currentPassword, currentEncryptedPassword)) {
                    b[0] = false;
                } else {
                    b[0] = true;
                }
            });
        return b[0];
    }

    @Transactional(readOnly = true)
    public Page<UserDTO> getAllManagedUsers(Pageable pageable) {
        return userRepository.findAllByLoginNot(pageable, Constants.ANONYMOUS_USER).map(UserDTO::new);
    }

    @Transactional(readOnly = true)
    public Optional<UserDTO> getUserWithAuthoritiesByLogin(String login) {
        return userRepository.findOneWithAuthoritiesByLogin(login).map(UserDTO::new);
    }

    @Transactional(readOnly = true)
    public Optional<User> getUserWithAuthorities(Long id) {
        return userRepository.findOneWithAuthoritiesById(id);
    }

    @Transactional(readOnly = true)
    public Optional<User> getUserWithAuthorities() {
        return SecurityUtils.getCurrentUserLogin().flatMap(userRepository::findOneWithAuthoritiesByLogin);
    }

    /**
     * Not activated users should be automatically deleted after 3 days.
     * <p>
     * This is scheduled to get fired everyday, at 01:00 (am).
     */
    @Scheduled(cron = "0 0 1 * * ?")
    public void removeNotActivatedUsers() {
        userRepository
            .findAllByActivatedIsFalseAndCreatedDateBefore(Instant.now().minus(3, ChronoUnit.DAYS))
            .forEach(user -> {
                log.debug("Deleting not activated user {}", user.getLogin());
                userRepository.delete(user);
                this.clearUserCaches(user);
            });
    }

    /**
     * @return a list of all the authorities
     */
    public List<String> getAuthorities() {
        return authorityRepository.findAll().stream().map(Authority::getName).collect(Collectors.toList());
    }

    public Page<UserDTO> getUsersPage(Pageable pageable) {
        return userRepository.findAll(pageable).map(UserDTO::new);
    }

    private void clearUserCaches(User user) {
        Objects.requireNonNull(cacheManager.getCache(UserRepository.USERS_BY_LOGIN_CACHE)).evict(user.getLogin());
        Objects.requireNonNull(cacheManager.getCache(UserRepository.USERS_BY_EMAIL_CACHE)).evict(user.getEmail());
        Objects.requireNonNull(cacheManager.getCache(EMAIL_CAPTCHA_CACHE)).evict("emailCaptcha");
    }
}
