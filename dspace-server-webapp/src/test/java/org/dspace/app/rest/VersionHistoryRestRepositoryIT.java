/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import static com.jayway.jsonpath.JsonPath.read;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;

import org.dspace.app.rest.matcher.VersionHistoryMatcher;
import org.dspace.app.rest.matcher.VersionMatcher;
import org.dspace.app.rest.matcher.WorkspaceItemMatcher;
import org.dspace.app.rest.test.AbstractControllerIntegrationTest;
import org.dspace.authorize.AuthorizeException;
import org.dspace.builder.CollectionBuilder;
import org.dspace.builder.CommunityBuilder;
import org.dspace.builder.ItemBuilder;
import org.dspace.builder.VersionBuilder;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.Item;
import org.dspace.content.WorkspaceItem;
import org.dspace.content.service.WorkspaceItemService;
import org.dspace.services.ConfigurationService;
import org.dspace.versioning.Version;
import org.dspace.versioning.VersionHistory;
import org.dspace.versioning.service.VersionHistoryService;
import org.dspace.versioning.service.VersioningService;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration test class for the VersionHistory endpoint.
 *
 * @author Mykhaylo Boychuk (mykhaylo.boychuk at 4science.it)
 */
public class VersionHistoryRestRepositoryIT extends AbstractControllerIntegrationTest {

    VersionHistory versionHistory;
    Item item;

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    private VersionHistoryService versionHistoryService;

    @Autowired
    private VersioningService versioningService;

    @Autowired
    private WorkspaceItemService workspaceItemService;

    @Before
    public void setup() throws SQLException, AuthorizeException {
        context.turnOffAuthorisationSystem();
        versionHistory = versionHistoryService.create(context);
        //** GIVEN **
        //1. A community-collection structure with one parent community with sub-community and two collections.
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();
        Community child1 = CommunityBuilder.createSubCommunity(context, parentCommunity)
                                           .withName("Sub Community")
                                           .build();
        Collection col1 = CollectionBuilder.createCollection(context, child1).withName("Collection 1").build();

        //2. Three public items that are readable by Anonymous with different subjects
        item = ItemBuilder.createItem(context, col1)
                          .withTitle("Public item 1")
                          .withIssueDate("2017-10-17")
                          .withAuthor("Smith, Donald").withAuthor("Doe, John")
                          .withSubject("ExtraEntry")
                          .build();
        context.restoreAuthSystemState();
    }

    @Test
    public void findOnePublicVersionHistoryTest() throws Exception {
        getClient().perform(get("/api/versioning/versionhistories/" + versionHistory.getID()))
                   .andExpect(status().isOk())
                   .andExpect(jsonPath("$", is(VersionHistoryMatcher.matchEntry(versionHistory))))
                   .andExpect(jsonPath("$._links.versions.href", Matchers.allOf(Matchers.containsString(
                                       "api/versioning/versionhistories/" + versionHistory.getID() + "/versions"))))
                   .andExpect(jsonPath("$._links.draftVersion.href", Matchers.allOf(Matchers.containsString(
                                       "api/versioning/versionhistories/" + versionHistory.getID() + "/draftVersion"))))
                   .andExpect(jsonPath("$._links.self.href", Matchers.allOf(Matchers.containsString(
                                       "api/versioning/versionhistories/" + versionHistory.getID()))));
    }

    @Test
    public void findOnePrivateVersionHistoryByAdminTest() throws Exception {
        configurationService.setProperty("versioning.item.history.view.admin", true);

        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test")
                                          .build();

        Item item = ItemBuilder.createItem(context, col)
                               .withTitle("Public test item")
                               .withIssueDate("2021-04-27")
                               .withAuthor("Doe, John")
                               .withSubject("ExtraEntry")
                               .build();

        VersionBuilder.createVersion(context, item, "test").build();
        VersionHistory versionHistory = versionHistoryService.findByItem(context, item);
        context.turnOffAuthorisationSystem();

        String adminToken = getAuthToken(admin.getEmail(), password);
        getClient(adminToken).perform(get("/api/versioning/versionhistories/" + versionHistory.getID()))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$.draftVersion", Matchers.is(true)))
                             .andExpect(jsonPath("$", is(VersionHistoryMatcher.matchEntry(versionHistory))));

        configurationService.setProperty("versioning.item.history.view.admin", false);

    }

    @Test
    public void findOneForbiddenTest() throws Exception {
        configurationService.setProperty("versioning.item.history.view.admin", true);

        String token = getAuthToken(eperson.getEmail(), password);
        getClient(token).perform(get("/api/versioning/versionhistories/" + versionHistory.getID()))
                        .andExpect(status().isForbidden());

        configurationService.setProperty("versioning.item.history.view.admin", false);
    }

    @Test
    public void findOneWrongIDTest() throws Exception {
        int wrongVersionHistoryId = (versionHistory.getID() + 5) * 57;
        getClient().perform(get("/api/versioning/versionhistories/" + wrongVersionHistoryId))
                   .andExpect(status().isNotFound());

    }
    @Test
    public void findVersionsOneWrongIDTest() throws Exception {
        int wrongVersionHistoryId = (versionHistory.getID() + 5) * 57;
        getClient().perform(get("/api/versioning/versionhistories/" + wrongVersionHistoryId + "/versions"))
                   .andExpect(status().isNotFound());

    }

    @Test
    public void findVersionsOfVersionHistoryAdminTest() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test")
                                          .build();

        Item item = ItemBuilder.createItem(context, col)
                               .withTitle("Public test item")
                               .withIssueDate("2021-04-27")
                               .withAuthor("Doe, John")
                               .withSubject("ExtraEntry")
                               .build();

        Version version = VersionBuilder.createVersion(context, item, "test").build();
        VersionHistory versionHistory = versionHistoryService.findByItem(context, item);
        Version version2 = versioningService.getVersion(context, item);
        context.turnOffAuthorisationSystem();

        String tokenAdmin = getAuthToken(admin.getEmail(), password);
        getClient(tokenAdmin).perform(get("/api/versioning/versionhistories/" + versionHistory.getID() + "/versions"))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$._embedded.versions", containsInAnyOrder(
                                                 VersionMatcher.matchEntry(version),
                                                 VersionMatcher.matchEntry(version2)
                                                 )));

        context.turnOffAuthorisationSystem();
        Version version3 = VersionBuilder.createVersion(context, item, "test3").build();
        context.restoreAuthSystemState();

        getClient(tokenAdmin).perform(get("/api/versioning/versionhistories/" + versionHistory.getID() + "/versions"))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$._embedded.versions", containsInAnyOrder(
                                                 VersionMatcher.matchEntry(version),
                                                 VersionMatcher.matchEntry(version2),
                                                 VersionMatcher.matchEntry(version3)
                                                 )));
    }

    @Test
    public void findVersionsOfVersionHistoryUnauthorizedTest() throws Exception {
        configurationService.setProperty("versioning.item.history.view.admin", true);
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test")
                                          .build();

        Item item = ItemBuilder.createItem(context, col)
                               .withTitle("Public test item")
                               .withIssueDate("2021-04-27")
                               .withAuthor("Doe, John")
                               .withSubject("ExtraEntry")
                               .build();

        VersionBuilder.createVersion(context, item, "test").build();
        VersionHistory versionHistory = versionHistoryService.findByItem(context, item);
        context.turnOffAuthorisationSystem();

        getClient().perform(get("/api/versioning/versionhistories/" + versionHistory.getID() + "/versions"))
                   .andExpect(status().isUnauthorized());

        configurationService.setProperty("versioning.item.history.view.admin", true);
    }

    @Test
    public void findVersionsOfVersionHistoryLoggedUserTest() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test")
                                          .build();

        Item item = ItemBuilder.createItem(context, col)
                               .withTitle("Public test item")
                               .withIssueDate("2021-04-27")
                               .withAuthor("Doe, John")
                               .withSubject("ExtraEntry")
                               .build();

        Version version = VersionBuilder.createVersion(context, item, "test").build();
        VersionHistory versionHistory = versionHistoryService.findByItem(context, item);
        Version version2 = versioningService.getVersion(context, item);
        context.turnOffAuthorisationSystem();

        String tokenEperson = getAuthToken(eperson.getEmail(), password);
        getClient(tokenEperson).perform(get("/api/versioning/versionhistories/" + versionHistory.getID() + "/versions"))
                               .andExpect(status().isOk())
                               .andExpect(jsonPath("$._embedded.versions", containsInAnyOrder(
                                                   VersionMatcher.matchEntry(version),
                                                   VersionMatcher.matchEntry(version2)
                                                   )));
    }

    @Test
    public void findVersionsOfVersionHistoryPaginationTest() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test")
                                          .build();

        Item item = ItemBuilder.createItem(context, col)
                               .withTitle("Public test item")
                               .withIssueDate("2021-04-27")
                               .withAuthor("Doe, John")
                               .withSubject("ExtraEntry")
                               .build();

        Version v2 = VersionBuilder.createVersion(context, item, "test").build();
        VersionHistory versionHistory = versionHistoryService.findByItem(context, item);
        Version v1 = versioningService.getVersion(context, item);
        Version v3 = VersionBuilder.createVersion(context, item, "test3").build();
        context.turnOffAuthorisationSystem();

        String tokenAdmin = getAuthToken(admin.getEmail(), password);
        getClient(tokenAdmin).perform(get("/api/versioning/versionhistories/" + versionHistory.getID() + "/versions")
                             .param("size", "1"))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$._embedded.versions", contains(VersionMatcher.matchEntry(v3))))
                             .andExpect(jsonPath("$.page.size", is(1)))
                             .andExpect(jsonPath("$.page.number", is(0)))
                             .andExpect(jsonPath("$.page.totalPages", is(3)))
                             .andExpect(jsonPath("$.page.totalElements", is(3)));

        getClient(tokenAdmin).perform(get("/api/versioning/versionhistories/" + versionHistory.getID() + "/versions")
                             .param("size", "1")
                             .param("page", "1"))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$._embedded.versions", contains(VersionMatcher.matchEntry(v2))))
                             .andExpect(jsonPath("$.page.size", is(1)))
                             .andExpect(jsonPath("$.page.number", is(1)))
                             .andExpect(jsonPath("$.page.totalPages", is(3)))
                             .andExpect(jsonPath("$.page.totalElements", is(3)));

        getClient(tokenAdmin).perform(get("/api/versioning/versionhistories/" + versionHistory.getID() + "/versions")
                             .param("size", "1")
                             .param("page", "2"))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$._embedded.versions", contains(VersionMatcher.matchEntry(v1))))
                             .andExpect(jsonPath("$.page.size", is(1)))
                             .andExpect(jsonPath("$.page.number", is(2)))
                             .andExpect(jsonPath("$.page.totalPages", is(3)))
                             .andExpect(jsonPath("$.page.totalElements", is(3)));
    }

    @Test
    public void findWorkspaceItemOfDraftVersionAdminTest() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test")
                                          .build();

        Item item = ItemBuilder.createItem(context, col)
                               .withTitle("Public test item")
                               .withIssueDate("2021-04-27")
                               .withAuthor("Doe, John")
                               .withSubject("ExtraEntry")
                               .build();

        Version v2 = VersionBuilder.createVersion(context, item, "test").build();
        VersionHistory vh = versionHistoryService.findByItem(context, item);
        WorkspaceItem witem = workspaceItemService.findByItem(context, v2.getItem());
        context.turnOffAuthorisationSystem();

        String tokenAdmin = getAuthToken(admin.getEmail(), password);
        getClient(tokenAdmin).perform(get("/api/versioning/versionhistories/" + vh.getID() + "/draftVersion"))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$",Matchers.is(WorkspaceItemMatcher
                                        .matchItemWithTitleAndDateIssuedAndSubject(witem,
                                         "Public test item", "2021-04-27", "ExtraEntry"))));
    }

    @Test
    public void findWorkspaceItemOfDraftVersionUnauthorizedTest() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test")
                                          .build();

        Item item = ItemBuilder.createItem(context, col)
                               .withTitle("Public test item")
                               .withIssueDate("2021-04-27")
                               .withAuthor("Doe, John")
                               .withSubject("ExtraEntry")
                               .build();

        VersionBuilder.createVersion(context, item, "test").build();
        VersionHistory vh = versionHistoryService.findByItem(context, item);
        context.turnOffAuthorisationSystem();

        getClient().perform(get("/api/versioning/versionhistories/" + vh.getID() + "/draftVersion"))
                   .andExpect(status().isUnauthorized());
    }

    @Test
    public void findWorkspaceItemOfDraftVersionLoggedUserTest() throws Exception {
        context.turnOffAuthorisationSystem();
        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test")
                                          .build();

        Item item = ItemBuilder.createItem(context, col)
                               .withTitle("Public test item")
                               .withIssueDate("2021-04-27")
                               .withAuthor("Doe, John")
                               .withSubject("ExtraEntry")
                               .build();

        Version v2 = VersionBuilder.createVersion(context, item, "test").build();
        VersionHistory vh = versionHistoryService.findByItem(context, item);
        WorkspaceItem witem = workspaceItemService.findByItem(context, v2.getItem());
        context.turnOffAuthorisationSystem();

        String ePersonToken = getAuthToken(eperson.getEmail(), password);
        getClient(ePersonToken).perform(get("/api/versioning/versionhistories/" + vh.getID() + "/draftVersion"))
                               .andExpect(status().isOk())
                               .andExpect(jsonPath("$",Matchers.is(WorkspaceItemMatcher
                                          .matchItemWithTitleAndDateIssuedAndSubject(witem,
                                           "Public test item", "2021-04-27", "ExtraEntry"))));
    }

    @Test
    @Ignore
    public void deleteVersionXXXXTest() throws Exception {
        //disable file upload mandatory
        configurationService.setProperty("webui.submit.upload.required", false);
        context.turnOffAuthorisationSystem();

        parentCommunity = CommunityBuilder.createCommunity(context)
                                          .withName("Parent Community")
                                          .build();

        Collection col = CollectionBuilder.createCollection(context, parentCommunity)
                                          .withName("Collection test")
                                          .withSubmitterGroup(admin)
                                          .build();

        Item item = ItemBuilder.createItem(context, col)
                               .withTitle("Public test item")
                               .withIssueDate("2021-03-20")
                               .withAuthor("Doe, John")
                               .withSubject("ExtraEntry")
                               .build();

        Version v2 = VersionBuilder.createVersion(context, item, "test").build();
        VersionHistory versionHistory = versionHistoryService.findByItem(context, item);
        Item lastVersionItem = v2.getItem();
        Version v1 = versioningService.getVersion(context, item);

        context.restoreAuthSystemState();
        AtomicReference<Integer> idRef = new AtomicReference<Integer>();
        String adminToken = getAuthToken(admin.getEmail(), password);
        Integer versionID = v2.getID();
        Item versionItem = v2.getItem();

        // item that linked last version is not archived
        getClient(adminToken).perform(get("/api/core/items/" + lastVersionItem.getID()))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$.inArchive", Matchers.is(false)));

        // retrieve the workspace item
        getClient(adminToken).perform(get("/api/submission/workspaceitems/search/item")
                             .param("uuid", String.valueOf(lastVersionItem.getID())))
                             .andExpect(status().isOk())
                             .andDo(result -> idRef.set(read(result.getResponse().getContentAsString(), "$.id")));

        // submit the workspaceitem to complete the deposit
        getClient(adminToken).perform(post(BASE_REST_SERVER_URL + "/api/workflow/workflowitems")
                             .content("/api/submission/workspaceitems/" + idRef.get())
                             .contentType(textUriContentType))
                             .andExpect(status().isCreated());

        // now the item is archived
        getClient(adminToken).perform(get("/api/core/items/" + lastVersionItem.getID()))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$.inArchive", Matchers.is(true)));

        getClient(adminToken).perform(get("/api/versioning/versions/" + versionID))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$", Matchers.is(VersionMatcher.matchEntry(v2))))
                             .andExpect(jsonPath("$._links.versionhistory.href", Matchers.allOf(Matchers.containsString(
                                                 "api/versioning/versions/" + v2.getID() + "/versionhistory"))))
                             .andExpect(jsonPath("$._links.item.href", Matchers.allOf(Matchers.containsString(
                                                 "api/versioning/versions/" + v2.getID() + "/item"))))
                             .andExpect(jsonPath("$._links.self.href", Matchers.allOf(Matchers.containsString(
                                                 "api/versioning/versions/" + v2.getID()))));

        getClient(adminToken).perform(get("/api/versioning/versionhistories/" + versionHistory.getID() + "/versions")
                             .param("size", "1"))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$._embedded.versions", contains(VersionMatcher.matchEntry(v2))))
                             .andExpect(jsonPath("$.page.size", is(1)))
                             .andExpect(jsonPath("$.page.number", is(0)))
                             .andExpect(jsonPath("$.page.totalPages", is(2)))
                             .andExpect(jsonPath("$.page.totalElements", is(2)));

        // To delete a version you need to delete the item linked to it.
        getClient(adminToken).perform(delete("/api/core/items/" + versionItem.getID()))
                             .andExpect(status().is(204));

        getClient(adminToken).perform(get("/api/versioning/versionhistories/" + versionHistory.getID() + "/versions")
                             .param("size", "1"))
                             .andExpect(status().isOk())
                             .andExpect(jsonPath("$._embedded.versions", contains(VersionMatcher.matchEntry(v1))))
                             .andExpect(jsonPath("$.page.size", is(1)))
                             .andExpect(jsonPath("$.page.number", is(0)))
                             .andExpect(jsonPath("$.page.totalPages", is(1)))
                             .andExpect(jsonPath("$.page.totalElements", is(1)));
    }

}