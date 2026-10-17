package com.localgov.web.security;

import com.localgov.domain.model.Employee;
import com.localgov.domain.model.UserAccount;
import com.localgov.repository.EmployeeRepository;
import com.localgov.repository.UserAccountRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserAccountRepository userAccountRepository;
    private final EmployeeRepository employeeRepository;

    public DatabaseUserDetailsService(UserAccountRepository userAccountRepository,
                                      EmployeeRepository employeeRepository) {
        this.userAccountRepository = userAccountRepository;
        this.employeeRepository = employeeRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount account = userAccountRepository.findByUsernameIgnoreCase(username)
                .filter(item -> Boolean.TRUE.equals(item.getActive()))
                .or(() -> userAccountRepository.findByEmployeeId(username)
                        .filter(item -> Boolean.TRUE.equals(item.getActive())))
                .or(() -> employeeRepository.findByEmployeeId(username)
                        .flatMap(emp -> userAccountRepository.findByEmployeeId(emp.getEmployeeId())
                                .filter(item -> Boolean.TRUE.equals(item.getActive()))))
                .orElseThrow(() -> new UsernameNotFoundException("User account not found: " + username));

        List<String> roles = parseRoles(account.getRolesCsv());
        if (roles.isEmpty()) {
            throw new UsernameNotFoundException("No roles configured for user account: " + username);
        }

        String authorityCode = resolveAuthorityCode(account);

        return new TenantUser(
                account.getUsername(),
                account.getPasswordHash(),
                Boolean.TRUE.equals(account.getActive()),
                true,  // accountNonExpired
                true,  // credentialsNonExpired
                true,  // accountNonLocked
                roles.stream()
                        .map(r -> (org.springframework.security.core.GrantedAuthority)
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + r))
                        .toList(),
                authorityCode
        );
    }

    /**
     * Derives the tenant identifier for a user account.
     *
     * Priority:
     *   1. erp_user_account.authority_code (authoritative when set)
     *   2. employees.authority_code (fallback for legacy NULL rows)
     *
     * Throws if neither path yields a value — fail closed. A user with
     * no resolvable tenant must not be able to authenticate into a
     * payroll-scoped session.
     */
    private String resolveAuthorityCode(UserAccount account) {
        String fromAccount = account.getAuthorityCode();
        if (fromAccount != null && !fromAccount.isBlank()) {
            return fromAccount;
        }
        String employeeId = account.getEmployeeId();
        if (employeeId != null) {
            return employeeRepository.findByEmployeeId(employeeId)
                    .map(Employee::getAuthorityCode)
                    .filter(code -> code != null && !code.isBlank())
                    .orElseThrow(() -> new UsernameNotFoundException(
                            "No authority_code resolvable for user " + account.getUsername()));
        }
        throw new UsernameNotFoundException(
                "No authority_code resolvable for user " + account.getUsername());
    }

    private List<String> parseRoles(String rolesCsv) {
        if (rolesCsv == null || rolesCsv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rolesCsv.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(item -> item.toUpperCase(Locale.ROOT))
                .map(item -> item.startsWith("ROLE_") ? item.substring(5) : item)
                .distinct()
                .toList();
    }
}
