package com.havita.modules.identity.application;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.modules.staff.infrastructure.StaffMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GlobalEmailValidationService {

    private final UserRepository userRepository;
    private final ResidentRepository residentRepository;
    private final StaffMemberRepository staffMemberRepository;

    @Transactional(readOnly = true)
    public void validateEmailUniqueness(String email) {
        validateEmailUniqueness(email, null, null, null);
    }
    
    @Transactional(readOnly = true)
    public void validateEmailUniqueness(String email, UUID excludeUserId, UUID excludeResidentId, UUID excludeStaffId) {
        if (email == null || email.isBlank()) {
            return;
        }
        
        String cleanEmail = email.trim().toLowerCase();
        
        // Find in User
        userRepository.findByEmailIgnoreCase(cleanEmail).ifPresent(user -> {
            if (excludeUserId == null || !user.getId().equals(excludeUserId)) {
                throw new BusinessRuleConflictException("EMAIL_ALREADY_EXISTS", "El correo electrónico ya está registrado por otro usuario.");
            }
        });

        // Find in Resident
        residentRepository.findByEmailIgnoreCase(cleanEmail).forEach(resident -> {
            if (excludeResidentId == null || !resident.getId().equals(excludeResidentId)) {
                if (resident.getUser() != null && excludeUserId != null && resident.getUser().getId().equals(excludeUserId)) {
                    // Valid if we are updating the same user linked to this resident
                } else {
                    throw new BusinessRuleConflictException("EMAIL_ALREADY_EXISTS", "El correo electrónico ya está registrado en el censo de residentes.");
                }
            }
        });

        // Find in StaffMember
        staffMemberRepository.findByEmailIgnoreCase(cleanEmail).forEach(staff -> {
             if (excludeStaffId == null || !staff.getId().equals(excludeStaffId)) {
                 if (staff.getUser() != null && excludeUserId != null && staff.getUser().getId().equals(excludeUserId)) {
                     // Valid if we are updating the same user linked to this staff
                 } else {
                     throw new BusinessRuleConflictException("EMAIL_ALREADY_EXISTS", "El correo electrónico ya está registrado para un miembro del personal.");
                 }
             }
        });
    }
}
