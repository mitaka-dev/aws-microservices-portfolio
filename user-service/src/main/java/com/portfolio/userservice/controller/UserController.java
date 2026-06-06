package com.portfolio.userservice.controller;

import com.portfolio.userservice.dto.CreateUserRequest;
import com.portfolio.userservice.dto.PagedResponse;
import com.portfolio.userservice.dto.UserResponse;
import com.portfolio.userservice.service.UserService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createUser(@RequestBody @Valid CreateUserRequest request) {
        return userService.createUser(request);
    }

    @GetMapping
    public PagedResponse<UserResponse> listUsers(@PageableDefault(size = 20) Pageable pageable) {
        return userService.listUsers(pageable);
    }

    @GetMapping("/{id}")
    public UserResponse getUser(@PathVariable Long id) {
        return userService.getUser(id);
    }
}
