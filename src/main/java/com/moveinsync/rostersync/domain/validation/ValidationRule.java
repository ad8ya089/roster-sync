package com.moveinsync.rostersync.domain.validation;

import java.util.List;

@FunctionalInterface
public interface ValidationRule<T> { List<FieldError> validate(T input); }
