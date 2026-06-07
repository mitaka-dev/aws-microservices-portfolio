package com.portfolio.userservice.service;

import com.portfolio.userservice.dto.CreateUserRequest;
import com.portfolio.userservice.dto.PagedResponse;
import com.portfolio.userservice.dto.UserResponse;
import com.portfolio.userservice.model.User;
import com.portfolio.userservice.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final MeterRegistry meterRegistry;

    public UserService(UserRepository userRepository, MeterRegistry meterRegistry) {
        this.userRepository = userRepository;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        try {
            var user = new User(request.email(), request.name());
            UserResponse response = UserResponse.from(userRepository.save(user));
            meterRegistry.counter("users.created.total").increment();
            return response;
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }
    }

    public UserResponse getUser(Long id) {
        return userRepository.findById(id)
            .map(UserResponse::from)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    public PagedResponse<UserResponse> listUsers(Pageable pageable) {
        var page = userRepository.findAll(pageable);
        return new PagedResponse<>(
            page.getContent().stream().map(UserResponse::from).toList(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements()
        );
    }
}
