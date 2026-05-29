package com.portfolio.userservice.service;

import com.portfolio.userservice.dto.CreateUserRequest;
import com.portfolio.userservice.dto.UserResponse;
import com.portfolio.userservice.model.User;
import com.portfolio.userservice.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        var user = new User(request.email(), request.name());
        return UserResponse.from(userRepository.save(user));
    }

    public UserResponse getUser(Long id) {
        return userRepository.findById(id)
            .map(UserResponse::from)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
