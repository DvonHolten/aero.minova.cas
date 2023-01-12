package aero.minova.cas.controller;

import static org.junit.Assert.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import aero.minova.cas.service.model.Authorities;
import aero.minova.cas.service.model.LuUserPrivilegeUserGroup;
import aero.minova.cas.service.model.UserGroup;
import aero.minova.cas.service.model.Users;
import aero.minova.cas.service.repository.AuthoritiesRepository;
import aero.minova.cas.service.repository.LuUserPrivilegeUserGroupRepository;
import aero.minova.cas.service.repository.UserGroupRepository;
import aero.minova.cas.service.repository.UserPrivilegeRepository;
import aero.minova.cas.service.repository.UsersRepository;

@SpringBootTest
class AuthorizationTest {

	@Autowired
	AuthorizationController controller;

	@Autowired
	AuthoritiesRepository authoritiesRepository;

	@Autowired
	LuUserPrivilegeUserGroupRepository luUserPrivilegeUserGroupRepository;

	@Autowired
	UserGroupRepository userGroupRepository;

	@Autowired
	UserPrivilegeRepository userPrivilegeRepository;

	@Autowired
	UsersRepository usersRepository;

	@Test
	void testAdminUser() {

		String username = "Testusername";

		controller.findOrCreateUserPrivilege("xvcorTEST");
		controller.findOrCreateUserPrivilege("xpcorAnotherone");

		controller.createOrUpdateAdminUser(username, "asfiusdhvn");

		// Wurde Nutzer erstellt?
		Users user = usersRepository.findByUsername(username).get();
		assertNotNull(user);

		// Wurde Admin Gruppe erstellt?
		UserGroup group = userGroupRepository.findByKeyText("admin").get();
		assertNotNull(group);

		// Wurde Nutzer der admin-Gruppe zugeordnet?
		Authorities auth = authoritiesRepository.findByUsernameAndAuthority(username, "admin").get();
		assertNotNull(auth);

		// Wurden die beiden Privilegien angelegt?
		List<LuUserPrivilegeUserGroup> findAllWithLastActionGreaterZero = luUserPrivilegeUserGroupRepository.findAllWithLastActionGreaterZero();
		assertEquals(2, findAllWithLastActionGreaterZero.size());

		// Hat Admin-Nutzer Rechte auf alle Privilegien?
		for (LuUserPrivilegeUserGroup lu : findAllWithLastActionGreaterZero) {
			assertEquals(group.getKeyLong(), lu.getUsergroup().getKeyLong());
		}

		controller.findOrCreateUserPrivilege("xpcorLastOne");
		controller.createOrUpdateAdminUser(username, "asfiusdhvn"); // Recht auf neues Privileg

		// Hat Admin-Nutzer auch Rechte auf das neue Privileg?
		findAllWithLastActionGreaterZero = luUserPrivilegeUserGroupRepository.findAllWithLastActionGreaterZero();
		assertEquals(3, findAllWithLastActionGreaterZero.size());
		for (LuUserPrivilegeUserGroup lu : findAllWithLastActionGreaterZero) {
			assertEquals(group.getKeyLong(), lu.getUsergroup().getKeyLong());
		}
	}

	@Test
	void testChangePassword() {

		String username = "PasswortTest";

		// User mit erstem Passwort erstellen
		controller.findOrCreateUser(username, "firstPW");
		Users user = usersRepository.findByUsername(username).get();
		assertNotNull(user);
		assertEquals("firstPW", user.getPassword());

		// Passwort NICHT geändern
		controller.findOrCreateUser(username, "secondPW");
		user = usersRepository.findByUsername(username).get();
		assertNotNull(user);
		assertEquals("firstPW", user.getPassword());

	}

	@Test
	void testAppendSecurityToken() {

		String groupName = "testGroup";

		controller.createOrUpdateUserGroup(groupName, "#FirstToken");

		UserGroup group = userGroupRepository.findByKeyText(groupName).get();
		assertNotNull(group);
		assertEquals("#FirstToken", group.getSecuritytoken());

		// Sicherstellen, dass keine Duplikat-Token angehängt werden
		controller.createOrUpdateUserGroup(groupName, "#FirstToken");
		group = userGroupRepository.findByKeyText(groupName).get();
		assertNotNull(group);
		assertEquals("#FirstToken", group.getSecuritytoken());

		// Sicherstellen, dass neuer Token angehängt wird
		controller.createOrUpdateUserGroup(groupName, "#SecondToken");
		group = userGroupRepository.findByKeyText(groupName).get();
		assertNotNull(group);
		assertEquals("#FirstToken#SecondToken", group.getSecuritytoken());

		// Nochmal keine Duplikate testen
		controller.createOrUpdateUserGroup(groupName, "#FirstToken");
		group = userGroupRepository.findByKeyText(groupName).get();
		assertNotNull(group);
		assertEquals("#FirstToken#SecondToken", group.getSecuritytoken());
	}

}
