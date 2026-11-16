package com.aaa.aihealthcare.vo;

import java.util.List;

public record ReviewRequest(Integer rating, List<String> tags, String comment) {
}
