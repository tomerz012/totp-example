package org.projectlombok.security.totpexample.servlets;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.projectlombok.security.totpexample.Session;
import org.projectlombok.security.totpexample.SessionStore;
import org.projectlombok.security.totpexample.Totp;
import org.projectlombok.security.totpexample.UserStore;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;

public class SetupTotpServlet extends HttpServlet {
	// SECURITY NOTE: TODO - explain this in some more detail.
	private static final long DEFAULT_TIME_TO_LIVE = TimeUnit.MINUTES.toMillis(30);
	
	private final UserStore users;
	private final SessionStore sessions;
	private final Template setupTotpTemplate;
	private final Totp totp;
	
	public SetupTotpServlet(Configuration templates, UserStore users, SessionStore sessions, Totp totp) throws IOException {
		this.setupTotpTemplate = templates.getTemplate("setupTotp.html");
		this.users = users;
		this.sessions = sessions;
		this.totp = totp;
	}
	
	@Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		Session session = sessions.get(request.getParameter("err"));
		renderPage(response, session);
	}
	
	@Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String username = request.getParameter("username");
		String password = request.getParameter("password");
		String password2 = request.getParameter("password2");
		
		if (username == null || username.isEmpty()) {
			error(request, response, "You need to pick a username.");
			return;
		}
		
		if (users.userExists(username)) {
			error(request, response, "That username has been taken.");
			return;
		}
		
		if (password == null || password.isEmpty()) {
			error(request, response, "Please choose a password.");
			return;
		}
		
		if (password2 == null || password2.isEmpty()) {
			error(request, response, "Please repeat your preferred password in the 'confirm password' box.");
			return;
		}
		
		if (!password.equals(password2)) {
			error(request, response, "The passwords in both password boxes did not match.");
			return;
		}
		
		if (password.length() < 8) {
			/* SECURITY NOTE:
			 * Do not add any further restrictions to a password:
			 * 
			 * - Do not require a mix of uppercase and lowercase characters.
			 * - Do not require a digit or a special character.
			 * - Do not disallow passwords with spaces. Do not disallow any characters, in fact.
			 * - Do not disallow passwords that are _longer_ than some limit. Really long passwords are fine.
			 * 
			 * Such restrictions are proven not to actually do much. For example, if you require a digit, it'll
			 * usually be a '1' and it'll be at the end. This doesn't meaningfully increase entropy at all.
			 * 
			 * The existence of TOTP will help a lot in reducing the issues with users picking easily guessed passwords.
			 */
			error(request, response, "Passwords need to be at least 8 characters long.");
			return;
		}
		
		Session session = totp.startSetupTotp(username, "TOTP demo app");
		session.put("password", password);
		renderPage(response, session);
	}
	
	private String calculateCode(String secret, long delta) {
		/*
		 * We intentionally use reflection here; showing a user the correct code is not something you ever actually need or want,
		 * therefore this API call is intentionally not public. This demo is the only time you'd want to do that.
		 */
		
		try {
			Field keyValidationField = Totp.class.getDeclaredField("KEY_VALIDATION_WINDOW");
			keyValidationField.setAccessible(true);
			long keyValidation = (Long) keyValidationField.get(null);
			long tick = System.currentTimeMillis() / keyValidation + delta;
			Method toBytes = Totp.class.getDeclaredMethod("toBytes", String.class);
			toBytes.setAccessible(true);
			byte[] rawSecret = (byte[]) toBytes.invoke(null, secret);
			Method calculateCode = Totp.class.getDeclaredMethod("calculateCode", byte[].class, long.class);
			calculateCode.setAccessible(true);
			return (String) calculateCode.invoke(null, rawSecret, tick);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException("This isn't possible; mismatch between TOTP implementation version and TOTP demo version");
		}
	}
	
	private void renderPage(HttpServletResponse response, Session session) throws IOException, ServletException {
		Map<String, Object> root = new HashMap<>();
		root.put("uri", session.getOrDefault(Totp.SESSIONKEY_URI, null));
		root.put("key", session.getSessionKey());
		String secret = session.getOrDefault(Totp.SESSIONKEY_SECRET, null);
		root.put("secret", secret);
		String error = session.getOrDefault("errMsg", "");
		if (!error.isEmpty()) {
			root.put("errMsg", error);
		}
		
		root.put("correctTotpCode", calculateCode(secret, 0L));
		root.put("threeMinutesOutCode", calculateCode(secret, 6L));
		root.put("hourOutCode", calculateCode(secret, 120L));
		
		response.setContentType("text/html; charset=UTF-8");
		try (Writer out = response.getWriter()) {
			setupTotpTemplate.process(root, out);
		} catch (TemplateException e) {
			throw new ServletException("Template broken: setupTotp.html", e);
		}
	}
	
	private void error(HttpServletRequest request, HttpServletResponse response, String message) throws ServletException, IOException {
		Session session = sessions.create(DEFAULT_TIME_TO_LIVE);
		session.put("errMsg", message);
		response.sendRedirect("/signup?err=" + session.getSessionKey());
	}
}
