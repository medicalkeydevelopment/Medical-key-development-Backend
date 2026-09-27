package com.secure.hms.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VersionController {
	
	@GetMapping
	public ResponseEntity<Object> getVersionNumber()
	{
		return ResponseEntity.ok("Version number is 1000");
	}

}
