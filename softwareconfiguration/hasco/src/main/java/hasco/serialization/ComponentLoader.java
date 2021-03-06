package hasco.serialization;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.math3.geometry.euclidean.oned.Interval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import hasco.model.BooleanParameterDomain;
import hasco.model.CategoricalParameterDomain;
import hasco.model.Component;
import hasco.model.Dependency;
import hasco.model.NumericParameterDomain;
import hasco.model.Parameter;
import hasco.model.ParameterDomain;
import hasco.model.ParameterRefinementConfiguration;
import jaicore.basic.sets.SetUtil;
import jaicore.basic.sets.SetUtil.Pair;

public class ComponentLoader {

	private static final Logger L = LoggerFactory.getLogger(ComponentLoader.class);

	private final Map<Component, Map<Parameter, ParameterRefinementConfiguration>> paramConfigs = new HashMap<>();
	private final Collection<Component> components = new ArrayList<>();
	private final Set<String> parsedFiles = new HashSet<>();

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final Map<String, JsonNode> parameterMap = new HashMap<>();
	private final Set<String> uniqueComponentNames = new HashSet<>();

	private final Set<String> requiredInterfaces = new HashSet<>();
	private final Set<String> providedInterfaces = new HashSet<>();

	private final Map<String, JsonNode> componentMap = new HashMap<>();
	private final boolean checkRequiredInterfacesResolvable;

	public ComponentLoader() {
		this(false);
	}

	public ComponentLoader(final boolean checkRequiredInterfacesResolvable) {
		this.checkRequiredInterfacesResolvable = checkRequiredInterfacesResolvable;
	}

	public ComponentLoader(final File jsonFile) throws IOException {
		this();
		this.loadComponents(jsonFile);
	}

	public ComponentLoader(final File jsonFile, final boolean checkRequiredInterfacesResolvable) throws IOException {
		this(checkRequiredInterfacesResolvable);
		this.loadComponents(jsonFile);
	}

	private void parseFile(final File jsonFile) throws IOException {
		L.debug("Parse file {}...", jsonFile.getAbsolutePath());
		StringBuilder stringDescriptionSB = new StringBuilder();
		String line;
		try (BufferedReader br = new BufferedReader(new FileReader(jsonFile))) {
			while ((line = br.readLine()) != null) {
				stringDescriptionSB.append(line + "\n");
			}
		}
		String jsonDescription = stringDescriptionSB.toString();
		jsonDescription = jsonDescription.replaceAll("/\\*(.*)\\*/", "");

		JsonNode rootNode = this.objectMapper.readTree(jsonDescription);

		for (JsonNode elem : rootNode.path("parameters")) {
			this.parameterMap.put(elem.get("name").asText(), elem);
		}
		JsonNode includes = rootNode.path("include");

		File baseFolder = new File(jsonFile.getCanonicalPath());
		if (jsonFile.isFile()) {
			baseFolder = new File(jsonFile.getCanonicalFile().getParentFile().getCanonicalPath());
		}

		for (JsonNode includePathNode : includes) {
			String path = includePathNode.asText();
			File subFile = new File(baseFolder.getAbsolutePath() + File.separator + path);
			if (!this.parsedFiles.contains(subFile.getCanonicalPath())) {
				if (!subFile.exists()) {
					throw new IllegalArgumentException("Cannot load " + subFile.getName() + " as this file or folder does not exist in " + subFile.getParent());
				}
				if (subFile.isFile()) {
					this.parsedFiles.add(subFile.getCanonicalPath());
					this.parseFile(subFile.getCanonicalFile());
				} else {
					for (File subsubFile : subFile.listFiles()) {
						if (!this.parsedFiles.contains(subsubFile.getCanonicalPath()) && subsubFile.isFile() && subsubFile.getName().endsWith(".json")) {
							this.parsedFiles.add(subsubFile.getCanonicalPath());
							this.parseFile(subsubFile.getCanonicalFile());
						}
					}
					this.parsedFiles.add(subFile.getCanonicalPath());
				}
			}
		}
		this.readFromJson(rootNode);
	}

	public void readFromString(final String json) throws IOException {
		ObjectMapper mapper = new ObjectMapper();
		this.readFromJson(mapper.readTree(json));
	}

	private void readFromJson(final JsonNode rootNode) throws IOException {
		// get the array of components
		JsonNode components = rootNode.path("components");
		if (components != null) {
			Component c;
			for (JsonNode component : components) {
				c = new Component(component.get("name").asText());
				this.componentMap.put(c.getName(), component);

				if (!this.uniqueComponentNames.add(c.getName())) {
					throw new IllegalArgumentException("Noticed a component with duplicative component name: " + c.getName());
				}

				// add provided interfaces

				for (JsonNode providedInterface : component.path("providedInterface")) {
					c.addProvidedInterface(providedInterface.asText());
				}

				// add required interfaces
				for (JsonNode requiredInterface : component.path("requiredInterface")) {
					if (!requiredInterface.has("id")) {
						throw new IOException("No id has been specified for a required interface of " + c.getName());
					}
					if (!requiredInterface.has("name")) {
						throw new IOException("No name has been specified for a required interface of " + c.getName());
					}
					c.addRequiredInterface(requiredInterface.get("id").asText(), requiredInterface.get("name").asText());
				}

				Map<Parameter, ParameterRefinementConfiguration> paramConfig = new HashMap<>();

				for (JsonNode parameter : component.path("parameter")) {
					// name of the parameter
					String name = parameter.get("name").asText();
					// possible string params
					String[] stringParams = new String[] { "type", "values", "default" };
					String[] stringParamValues = new String[stringParams.length];
					// possible boolean params
					String[] boolParams = new String[] { "default" };
					boolean[] boolParamValues = new boolean[boolParams.length];
					// possible double params
					String[] doubleParams = new String[] { "default", "min", "max", "refineSplits", "minInterval" };
					double[] doubleParamValues = new double[doubleParams.length];

					if (this.parameterMap.containsKey(name)) {
						JsonNode commonParameter = this.parameterMap.get(name);
						// get string parameter values from common parameter
						for (int i = 0; i < stringParams.length; i++) {
							if (commonParameter.get(stringParams[i]) != null) {
								stringParamValues[i] = commonParameter.get(stringParams[i]).asText();
							}
						}
						// get double parameter values from common parameter
						for (int i = 0; i < doubleParams.length; i++) {
							if (commonParameter.get(doubleParams[i]) != null) {
								doubleParamValues[i] = commonParameter.get(doubleParams[i]).asDouble();
							}
						}
						// get boolean parameter values from common parameter
						for (int i = 0; i < boolParams.length; i++) {
							if (commonParameter.get(boolParams[i]) != null) {
								boolParamValues[i] = commonParameter.get(boolParams[i]).asBoolean();
							}
						}
					}

					// get string parameter values from current parameter
					for (int i = 0; i < stringParams.length; i++) {
						if (parameter.get(stringParams[i]) != null) {
							stringParamValues[i] = parameter.get(stringParams[i]).asText();
						}
					}
					// get double parameter values from current parameter
					for (int i = 0; i < doubleParams.length; i++) {
						if (parameter.get(doubleParams[i]) != null) {
							doubleParamValues[i] = parameter.get(doubleParams[i]).asDouble();
						}
					}
					// get boolean parameter values from current parameter
					for (int i = 0; i < boolParams.length; i++) {
						if (parameter.get(boolParams[i]) != null) {
							boolParamValues[i] = parameter.get(boolParams[i]).asBoolean();
						}
					}

					Parameter p = null;
					String type = stringParamValues[Arrays.stream(stringParams).collect(Collectors.toList()).indexOf("type")];
					switch (type) {
					case "int":
					case "double":
						p = new Parameter(name, new NumericParameterDomain(type.equals("int"), doubleParamValues[1], doubleParamValues[2]), doubleParamValues[0]);
						if (doubleParamValues[3] == 0) {
							throw new IllegalArgumentException("Please specify the parameter \"refineSplits\" for the parameter \"" + p.getName() + "\" in component \"" + c.getName() + "\"");
						}
						if (doubleParamValues[4] <= 0) {
							throw new IllegalArgumentException("Please specify a strictly positive parameter value for \"minInterval\" for the parameter \"" + p.getName() + "\" in component \"" + c.getName() + "\"");
						}
						paramConfig.put(p, new ParameterRefinementConfiguration((int) doubleParamValues[3], doubleParamValues[4]));
						break;
					case "bool":
					case "boolean":
						p = new Parameter(name, new BooleanParameterDomain(), boolParamValues[0]);
						break;
					case "cat":
						if (parameter.get("values") != null && parameter.get("values").isTextual()) {
							p = new Parameter(name, new CategoricalParameterDomain(Arrays.stream(stringParamValues[1].split(",")).collect(Collectors.toList())), stringParamValues[2]);
						} else {
							List<String> values = new LinkedList<>();

							if (parameter.get("values") != null) {
								for (JsonNode value : parameter.get("values")) {
									values.add(value.asText());
								}
							} else if (this.parameterMap.containsKey(name)) {
								for (JsonNode value : this.parameterMap.get(name).get("values")) {
									values.add(value.asText());
								}
							} else {
								L.error("Warning: Categorical parameter {} in component {} without value list.", name, c.getName());
							}
							p = new Parameter(name, new CategoricalParameterDomain(values), stringParamValues[2]);
						}
						break;
					default:
						throw new IllegalArgumentException("Unsupported parameter type " + type);
					}
					if (p != null) {
						c.addParameter(p);
					}
				}

				/* now parse dependencies */
				for (JsonNode dependency : component.path("dependencies")) {

					/* parse precondition */
					String pre = dependency.get("pre").asText();
					Collection<Collection<Pair<Parameter, ParameterDomain>>> premise = new ArrayList<>();
					Collection<String> monoms = Arrays.asList(pre.split("\\|"));
					for (String monom : monoms) {
						Collection<String> literals = Arrays.asList(monom.split("&"));
						Collection<Pair<Parameter, ParameterDomain>> monomInPremise = new ArrayList<>();

						for (String literal : literals) {
							String[] parts = literal.trim().split(" ");
							if (parts.length != 3) {
								throw new IllegalArgumentException("Cannot parse literal " + literal + ". Literals must be of the form \"<a> P <b>\".");
							}

							Parameter param = c.getParameterWithName(parts[0]);
							String target = parts[2];
							switch (parts[1]) {
							case "=": {
								Pair<Parameter, ParameterDomain> conditionItem;
								if (param.isNumeric()) {
									double val = Double.valueOf(target);
									conditionItem = new Pair<>(param, new NumericParameterDomain(((NumericParameterDomain) param.getDefaultDomain()).isInteger(), val, val));
								} else if (param.isCategorical()) {
									conditionItem = new Pair<>(param, new CategoricalParameterDomain(new String[] { target }));
								} else {
									throw new IllegalArgumentException("Currently no support for parameters with domain \"" + param.getDefaultDomain().getClass().getName() + "\"");
								}
								monomInPremise.add(conditionItem);
								break;
							}
							case "in": {
								Pair<Parameter, ParameterDomain> conditionItem;
								if (param.isNumeric()) {
									Interval interval = SetUtil.unserializeInterval("[" + target.substring(1, target.length() - 1) + "]");
									conditionItem = new Pair<>(param, new NumericParameterDomain(((NumericParameterDomain) param.getDefaultDomain()).isInteger(), interval.getInf(), interval.getSup()));
								} else if (param.isCategorical()) {
									if (!target.startsWith("[") && !target.startsWith("{")) {
										throw new IllegalArgumentException("Illegal literal \"" + literal + "\" in the postcondition of dependency. This should be a set, but the target is not described by [...] or {...}");
									}
									Collection<String> values = target.startsWith("[") ? SetUtil.unserializeList(target) : SetUtil.unserializeSet(target);
									conditionItem = new Pair<>(param, new CategoricalParameterDomain(values));
								} else {
									throw new IllegalArgumentException("Currently no support for parameters with domain \"" + param.getDefaultDomain().getClass().getName() + "\"");
								}
								monomInPremise.add(conditionItem);
								break;
							}
							default:
								throw new IllegalArgumentException("Cannot parse literal " + literal + ". Currently no support for predicate \"" + parts[1] + "\".");
							}
						}
						premise.add(monomInPremise);
					}

					/* parse postcondition */
					Collection<Pair<Parameter, ParameterDomain>> conclusion = new ArrayList<>();
					String post = dependency.get("post").asText();
					Collection<String> literals = Arrays.asList(post.split("&"));

					for (String literal : literals) {
						String[] parts = literal.trim().split(" ");
						if (parts.length < 3) {
							throw new IllegalArgumentException("Cannot parse literal " + literal + ". Literals must be of the form \"<a> P <b>\".");
						}
						if (parts.length > 3) {
							for (int i = 3; i < parts.length; i++) {
								parts[2] += " " + parts[i];
							}
						}

						Parameter param = c.getParameterWithName(parts[0]);
						String target = parts[2];
						switch (parts[1]) {
						case "=": {
							Pair<Parameter, ParameterDomain> conditionItem;
							if (param.isNumeric()) {
								double val = Double.valueOf(target);
								conditionItem = new Pair<>(param, new NumericParameterDomain(((NumericParameterDomain) param.getDefaultDomain()).isInteger(), val, val));
							} else if (param.isCategorical()) {
								conditionItem = new Pair<>(param, new CategoricalParameterDomain(new String[] { target }));
							} else {
								throw new IllegalArgumentException("Currently no support for parameters with domain \"" + param.getDefaultDomain().getClass().getName() + "\"");
							}
							conclusion.add(conditionItem);
							break;
						}
						case "in": {
							Pair<Parameter, ParameterDomain> conditionItem;
							if (param.isNumeric()) {
								Interval interval = SetUtil.unserializeInterval("[" + target.substring(1, target.length() - 1) + "]");
								conditionItem = new Pair<>(param, new NumericParameterDomain(((NumericParameterDomain) param.getDefaultDomain()).isInteger(), interval.getInf(), interval.getSup()));
							} else if (param.isCategorical()) {
								if (!target.startsWith("[") && !target.startsWith("{")) {
									throw new IllegalArgumentException("Illegal literal \"" + literal + "\" in the postcondition of dependency. This should be a set, but the target is not described by [...] or {...}");
								}
								Collection<String> values = target.startsWith("[") ? SetUtil.unserializeList(target) : SetUtil.unserializeSet(target);
								conditionItem = new Pair<>(param, new CategoricalParameterDomain(values));
							} else {
								throw new IllegalArgumentException("Currently no support for parameters with domain \"" + param.getDefaultDomain().getClass().getName() + "\"");
							}
							conclusion.add(conditionItem);
							break;
						}
						default:
							throw new IllegalArgumentException("Cannot parse literal " + literal + ". Currently no support for predicate \"" + parts[1] + "\".");
						}
					}
					/* add dependency to the component */
					c.addDependency(new Dependency(premise, conclusion));
				}

				this.paramConfigs.put(c, paramConfig);
				this.components.add(c);

				this.requiredInterfaces.addAll(c.getRequiredInterfaces().values());
				this.providedInterfaces.addAll(c.getProvidedInterfaces());
			}
		}
	}

	public ComponentLoader loadComponents(final File componentDescriptionFile) throws IOException, UnresolvableRequiredInterfaceException {
		this.paramConfigs.clear();
		this.components.clear();
		this.uniqueComponentNames.clear();
		this.requiredInterfaces.clear();
		this.providedInterfaces.clear();

		this.parseFile(componentDescriptionFile);

		if (this.checkRequiredInterfacesResolvable && !this.getUnresolvableRequiredInterfaces().isEmpty()) {
			throw new UnresolvableRequiredInterfaceException();
		}

		return this;
	}

	/**
	 * @return Returns the collection of required interfaces that cannot be resolved by a provided interface.
	 */
	public Collection<String> getUnresolvableRequiredInterfaces() {
		return SetUtil.difference(this.requiredInterfaces, this.providedInterfaces);
	}

	/**
	 * @param componentName
	 *            The name of the component.
	 * @return Returns the collection of required interfaces that cannot be resolved by a provided interface.
	 */
	public JsonNode getComponentAsJsonNode(final String componentName) {
		return this.componentMap.get(componentName);
	}

	/**
	 * @return The map describing for each component individually how its parameters may be refined.
	 */
	public Map<Component, Map<Parameter, ParameterRefinementConfiguration>> getParamConfigs() {
		return this.paramConfigs;
	}

	/**
	 * @return The collection of parsed components.
	 */
	public Collection<Component> getComponents() {
		return this.components;
	}

	/**
	 * This method searches for a component with the given name. If such a component does not exist, a NoSuchElementException is thrown.
	 * @param name The name of the component in question.
	 * @return The component for the given name.
	 */
	public Component getComponentWithName(final String name) {
		for (Component component : this.getComponents()) {
			if (component.getName().equals(name)) {
				return component;
			}
		}
		throw new NoSuchElementException("There is no component with the requested name");
	}

	public static void main(final String[] args) throws IOException, UnresolvableRequiredInterfaceException {
		ComponentLoader cl = new ComponentLoader();
		cl.loadComponents(new File("complexMLComponents.json"));
	}

	public Map<String, JsonNode> getJsonNodeComponents() {
		return this.componentMap;
	}

}
