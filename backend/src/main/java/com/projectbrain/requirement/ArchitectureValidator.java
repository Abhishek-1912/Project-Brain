package com.projectbrain.requirement;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Checks AI and human architecture content with the same rules, so nothing inconsistent is ever saved. */
@Component
class ArchitectureValidator {

	private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

	private static final Set<String> COLUMN_TYPES = Set.of("uuid", "text", "integer", "bigint", "numeric", "boolean",
			"date", "timestamptz", "jsonb");

	private static final Set<String> FIELD_TYPES = Set.of("string", "integer", "number", "boolean", "uuid", "date",
			"datetime", "object", "array");

	private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

	JsonNode overview(JsonNode content) {
		ObjectNode overview = object(content);
		require(text(overview.get("summary"), 5000));
		items(overview, "components", 30, item -> require(text(item.get("name"), 100)
				&& text(item.get("responsibility"), 1000) && optionalText(item.get("technology"), 200)));
		items(overview, "techStack", 30, item -> require(text(item.get("area"), 100) && text(item.get("choice"), 200)
				&& text(item.get("reason"), 1000)));
		items(overview, "decisions", 30, item -> {
			require(text(item.get("title"), 200) && text(item.get("decision"), 2000) && text(item.get("reason"), 2000));
			JsonNode alternatives = item.get("alternatives");
			require(alternatives != null && alternatives.isArray() && alternatives.size() <= 5);
			alternatives.forEach(alternative -> require(text(alternative, 500)));
		});
		items(overview, "risks", 30, item -> require(text(item.get("text"), 1000)));
		return overview;
	}

	JsonNode dataModel(JsonNode generated, Map<String, UUID> storyIdsByKey) {
		return dataModel(object(generated), item -> refsToIds(item, storyIdsByKey));
	}

	JsonNode editedDataModel(JsonNode content, Set<UUID> allowedStoryIds) {
		return dataModel(object(content), item -> keepAllowed(item, allowedStoryIds));
	}

	JsonNode apiDesign(JsonNode generated, Map<String, UUID> storyIdsByKey) {
		return apiDesign(object(generated), item -> refsToIds(item, storyIdsByKey));
	}

	JsonNode editedApiDesign(JsonNode content, Set<UUID> allowedStoryIds) {
		return apiDesign(object(content), item -> keepAllowed(item, allowedStoryIds));
	}

	/** Turns stored story IDs back into this run's story keys, dropping stories that are no longer included. */
	JsonNode toStepForm(JsonNode stored, Map<UUID, String> keysById) {
		ObjectNode copy = object(stored);
		for (String list : List.of("tables", "endpoints")) {
			JsonNode items = copy.get(list);
			if (items == null || !items.isArray()) {
				continue;
			}
			for (JsonNode item : items) {
				ObjectNode object = (ObjectNode) item;
				JsonNode ids = object.remove("storyIds");
				ArrayNode refs = object.putArray("storyRefs");
				if (ids != null && ids.isArray()) {
					ids.forEach(id -> {
						String key = keysById.get(uuid(id));
						if (key != null) {
							refs.add(key);
						}
					});
				}
			}
		}
		return copy;
	}

	private ObjectNode dataModel(ObjectNode model, Consumer<ObjectNode> links) {
		Map<String, Set<String>> columnsByTable = new HashMap<>();
		items(model, "tables", 60, table -> {
			String name = identifier(table.get("name"));
			require(text(table.get("description"), 1000) && !columnsByTable.containsKey(name));
			Set<String> columns = new HashSet<>();
			JsonNode list = table.get("columns");
			require(list != null && list.isArray() && list.size() >= 1 && list.size() <= 50);
			for (JsonNode column : list) {
				require(column.isObject() && columns.add(identifier(column.get("name"))));
				require(isString(column.get("type")) && COLUMN_TYPES.contains(column.get("type").asString()));
				require(column.get("nullable") != null && column.get("nullable").isBoolean());
				require(text(column.get("description"), 500));
			}
			columnsByTable.put(name, columns);
		});
		for (JsonNode table : model.get("tables")) {
			Set<String> own = columnsByTable.get(table.get("name").asString());
			JsonNode relations = table.get("relations");
			require(relations != null && relations.isArray() && relations.size() <= 20);
			for (JsonNode relation : relations) {
				require(relation.isObject() && own.contains(identifier(relation.get("column"))));
				Set<String> target = columnsByTable.get(identifier(relation.get("referencesTable")));
				require(target != null && target.contains(identifier(relation.get("referencesColumn"))));
			}
			links.accept((ObjectNode) table);
		}
		return model;
	}

	private ObjectNode apiDesign(ObjectNode design, Consumer<ObjectNode> links) {
		Set<String> seen = new HashSet<>();
		items(design, "endpoints", 150, endpoint -> {
			JsonNode method = endpoint.get("method");
			JsonNode path = endpoint.get("path");
			require(isString(method) && METHODS.contains(method.asString()));
			require(text(path, 200) && path.asString().startsWith("/"));
			require(seen.add(method.asString() + " " + path.asString()));
			require(text(endpoint.get("summary"), 500));
			for (String fields : List.of("requestFields", "responseFields")) {
				JsonNode list = endpoint.get(fields);
				require(list != null && list.isArray() && list.size() <= 40);
				for (JsonNode field : list) {
					require(field.isObject() && text(field.get("name"), 100) && text(field.get("description"), 500));
					require(isString(field.get("type")) && FIELD_TYPES.contains(field.get("type").asString()));
					require(field.get("required") != null && field.get("required").isBoolean());
				}
			}
			JsonNode roles = endpoint.get("roles");
			require(roles != null && roles.isArray() && roles.size() <= 10);
			roles.forEach(role -> require(text(role, 60)));
			links.accept(endpoint);
		});
		return design;
	}

	private static void refsToIds(ObjectNode item, Map<String, UUID> storyIdsByKey) {
		JsonNode refs = item.remove("storyRefs");
		Set<UUID> ids = new LinkedHashSet<>();
		if (refs != null && refs.isArray()) {
			refs.forEach(ref -> {
				UUID id = isString(ref) ? storyIdsByKey.get(ref.asString()) : null;
				if (id != null) {
					ids.add(id);
				}
			});
		}
		putIds(item, ids);
	}

	private static void keepAllowed(ObjectNode item, Set<UUID> allowed) {
		JsonNode given = item.remove("storyIds");
		item.remove("storyRefs");
		Set<UUID> ids = new LinkedHashSet<>();
		if (given != null && given.isArray()) {
			given.forEach(id -> {
				UUID value = uuid(id);
				if (value != null && allowed.contains(value)) {
					ids.add(value);
				}
			});
		}
		putIds(item, ids);
	}

	private static void putIds(ObjectNode item, Set<UUID> ids) {
		ArrayNode array = item.putArray("storyIds");
		ids.forEach(id -> array.add(id.toString()));
	}

	private static void items(ObjectNode parent, String field, int max, Consumer<ObjectNode> check) {
		JsonNode list = parent.get(field);
		require(list != null && list.isArray() && list.size() <= max);
		for (JsonNode item : list) {
			require(item.isObject());
			check.accept((ObjectNode) item);
		}
	}

	private static ObjectNode object(JsonNode node) {
		require(node != null && node.isObject());
		return (ObjectNode) node.deepCopy();
	}

	private static String identifier(JsonNode node) {
		require(isString(node) && IDENTIFIER.matcher(node.asString()).matches());
		return node.asString();
	}

	private static boolean text(JsonNode node, int max) {
		return isString(node) && !node.asString().isBlank() && node.asString().length() <= max;
	}

	private static boolean optionalText(JsonNode node, int max) {
		return node == null || node.isNull() || (isString(node) && node.asString().length() <= max);
	}

	private static boolean isString(JsonNode node) {
		return node != null && node.isString();
	}

	private static UUID uuid(JsonNode node) {
		try {
			return isString(node) ? UUID.fromString(node.asString()) : null;
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static void require(boolean valid) {
		if (!valid) {
			throw new InvalidAiOutputException();
		}
	}

}
