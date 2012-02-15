package com.attask.jenkins.dashboard;

import hudson.Extension;
import hudson.model.*;
import hudson.tasks.junit.TestResultAction;
import hudson.util.RunList;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * A view that shows a table of builds that are related (determined by build description and a regex).
 *
 * User: joeljohnson
 * Date: 2/9/12
 * Time: 9:56 AM
 */
public class PipelineDashboard extends View {
	public static Logger LOGGER = Logger.getLogger(PipelineDashboard.class.getSimpleName());
	public List<String> jobs;
    public String descriptionRegex;
    public int descriptionRegexGroup;
    public int numberDisplayed;
	public String firstColumnName;
	public boolean showBuildName;
	public boolean showFailureCount;
	public boolean clickForCommitDetails;

	@DataBoundConstructor
	public PipelineDashboard(String name) {
		super(name);
	}
	
	@SuppressWarnings("UnusedDeclaration")
	public static Collection<String> getAllJobs() {
		return Jenkins.getInstance().getJobNames();
	}

	@Override
	protected void submit(StaplerRequest request) throws ServletException, Descriptor.FormException, IOException {
		String jobsParameter = request.getParameter("_.jobs");
		this.jobs = new ArrayList<String>() {
			//Overriding to make it show up pretty in jenkins
			public String toString() {
				return join(this, ", ");
			}
		};
		if(jobsParameter != null) {
			for (String job : Arrays.asList(jobsParameter.split(","))) {
				jobs.add(job.trim());
			}
		}

		this.descriptionRegex = request.getParameter("_.descriptionRegex");
		String descriptionRegexGroup = request.getParameter("_.descriptionRegexGroup");
		if(descriptionRegexGroup != null) {
			this.descriptionRegexGroup = Integer.parseInt(descriptionRegexGroup);
			if(this.descriptionRegexGroup < 0) {
				this.descriptionRegexGroup = 0;
			}
		} else {
			this.descriptionRegexGroup = 0;
		}


		String numberDisplayed = request.getParameter("_.numberDisplayed");
		if(numberDisplayed == null || numberDisplayed.isEmpty()) {
			this.numberDisplayed = 5;
		} else {
			this.numberDisplayed = Integer.parseInt(numberDisplayed);
		}

		this.firstColumnName = request.getParameter("_.firstColumnName");
		
		this.showBuildName = "on".equals(request.getParameter("_.showBuildName"));
		this.showFailureCount = "on".equals(request.getParameter("_.showFailureCount"));
		this.clickForCommitDetails = "on".equals(request.getParameter("_.clickForCommitDetails"));
	}

	/**
	 * Finds all the builds that matches the criteria in the settings and organizes them in rows and columns.
	 * @return The list of Rows. Each row containing information about builds whose descriptions match based on the
	 * 			regex provided. The list is sorted by build date starting with the most recent.
	 */
	@SuppressWarnings("UnusedDeclaration")
	public List<Row> getDisplayRows() {
		LOGGER.info("getDisplayRows starting");

		Jenkins jenkins = Jenkins.getInstance();
		Map<String, Build[]> map = findMatchingBuilds(jenkins, jobs, descriptionRegex, descriptionRegexGroup);
		LOGGER.info("map size: " + map.size());

		List<Row> result = generateRowData(jenkins.getRootUrl(), User.current(), map, this.showBuildName, this.showFailureCount);
		LOGGER.info("result size: " + result.size());

		return result;
	}

	protected Map<String, Build[]> findMatchingBuilds(ItemGroup<TopLevelItem> jenkins, List<String> jobs, String descriptionRegex, int descriptionRegexGroup) {
		Map<String, Build[]> map = new HashMap<String, Build[]>();
		for (String jobName : jobs) {
			try {
				Job job = (Job) jenkins.getItem(jobName);
				RunList builds = job.getBuilds();
				for (Object buildObj : builds) {
					Build build = (Build)buildObj;
					String buildDescription = build.getDescription();
					if(buildDescription == null || "".equals(buildDescription.trim())) {
						continue;
					}
					buildDescription = buildDescription.replaceAll("(\n|\r)", " "); //normalize whitespace

					if(buildDescription.matches(descriptionRegex)) {
						String key = buildDescription.replaceFirst(descriptionRegex, "$" + descriptionRegexGroup);
						if(!map.containsKey(key)) {
							map.put(key, new Build[jobs.size()]);
						}
						map.get(key)[jobs.indexOf(jobName)] = build;
					}
				}
			} catch(Throwable t) {
				LOGGER.severe("Error while generating the map: " + t.getMessage() + "\n" + join(Arrays.asList(t.getStackTrace()), "\n"));
				if(t.getCause() != null) {
					LOGGER.severe("Nested Exception: " + t.getCause().getClass().getCanonicalName() + t.getCause().getMessage() + "\n" + join(Arrays.asList(t.getStackTrace()), "\n"));
				}
			}
		}
		return map;
	}

	protected List<Row> generateRowData(String rootUrl, User currentUser, Map<String, Build[]> map, boolean showBuildName, boolean showFailureCount) {
		SortedSet<Row> rows = new TreeSet<Row>(new Comparator<Row>() {
			public int compare(Row row1, Row row2) {
				if(row1 == row2) return 0;
				if(row1 == null) return 1;
				if(row2 == null) return -1;
				return -row1.getDate().compareTo(row2.getDate());
			}
		});

		for (String rowName : map.keySet()) {
			try {
				LOGGER.info("Row " + rowName);
				Build[] builds = map.get(rowName);
				List<Column> columns = new LinkedList<Column>();
				Date date = null;
				String displayName = rowName;
				boolean isCulprit = false;

				for (Build build : builds) {
					if(build != null) {
						LOGGER.info("\t" + build.getDisplayName() + " " + build.getDescription());
						if(date == null) date = build.getTime();

						String rowDisplayName = "";
						
						if(showBuildName) {
							rowDisplayName = build.getDisplayName();
						}
						
						if(showFailureCount) {
							String testResult = "";
							TestResultAction testResultAction = build.getAction(TestResultAction.class);
							if(testResultAction != null) {
								int failures = testResultAction.getFailCount();
								testResult = "(" + failures + " failures" + ")";
							}
							if(!rowDisplayName.isEmpty()) {
								rowDisplayName += " ";
							}
							rowDisplayName += testResult;
						}

						columns.add(new Column(rowDisplayName, build.getUrl(), rootUrl +"/static/832a5f9d/images/24x24/" + build.getBuildStatusUrl()));
					} else {
						LOGGER.info("\tAdded empty column");
						columns.add(Column.EMPTY);
					}
					//noinspection StringEquality
					if(displayName == rowName && build.getDescription() != null && !build.getDescription().trim().isEmpty()) { // I really do want to do reference equals and not value equals.
						displayName = build.getDescription();
						isCulprit = getUserIsCulprit(currentUser, build);
					}
				}
				if(date == null) date = new Date();

				rows.add(new Row(date, rowName, displayName, columns, isCulprit));
			} catch (Throwable t) {
				LOGGER.severe("Error while generating the list: " + t.getClass().getCanonicalName() + t.getMessage() + "\n" + join(Arrays.asList(t.getStackTrace()), "\n"));
				if(t.getCause() != null) {
					LOGGER.severe("Nested Exception: " + t.getCause().getClass().getCanonicalName() + t.getCause().getMessage() + "\n" + join(Arrays.asList(t.getStackTrace()), "\n"));
				}
			}
		}
		List<Row> result = new LinkedList<Row>();

		int i = 0;
		for (Row row : rows) {
			result.add(row);
			i++;
			if(i >= numberDisplayed || i >= rows.size()) {
				break;
			}
		}
		return result;
	}

	private boolean getUserIsCulprit(User currentUser, Build build) {
		if(currentUser == null || build == null) return false;

		String description = build.getDescription();
		if(description.contains(currentUser.getFullName()) || description.contains(currentUser.getId())) {
			return true;
		}

		//noinspection unchecked
		for (User culprit : (Set<User>)build.getCulprits()) {
			if((culprit.getId() != null && culprit.getId().equals(currentUser.getId())) || (culprit.getFullName() != null && culprit.getFullName().equals(currentUser.getFullName()))) {
				return true;
			}
		}
		
		return false;
	}

	/**
	 * Generates a flat string
	 * @param collection The collection to combine
	 * @param separator The string to separate each element with
	 * @return The toString of each element of the given collection, separated by the given separator.
	 */
	private String join(Collection<?> collection, String separator) {
		if(collection == null) return "";
		if(separator == null) separator = "";

		StringBuilder sb = new StringBuilder();
		for (Object s : collection) {
			sb.append(s).append(separator);
		}
		if(sb.length() > 0) {
			return sb.substring(0, sb.length() - separator.length());
		}
		return "";
	}


	@Override
	public Collection<TopLevelItem> getItems() {
		return Collections.emptyList();
	}

	@Override
	public boolean contains(TopLevelItem item) {
		return false;
	}

	@Override
	public void onJobRenamed(Item item, String oldName, String newName) {
		Collections.replaceAll(jobs, oldName, newName);
	}

	@Override
	public synchronized Item doCreateItem(StaplerRequest request, StaplerResponse response) throws IOException, ServletException {
		Item item = Jenkins.getInstance().doCreateItem(request, response);
		if (item != null) {
			jobs.add(item.getName());
			owner.save();
		}
		return item;
	}

	@Override
	public String toString() {
		return super.toString() + " { " +
				"description: " + this.description + ", " +
				"descriptionRegex: " + this.descriptionRegex + ", " +
				"firstColumnName: " + this.firstColumnName + ", " +
				"numberDisplayed: " + this.numberDisplayed + ", " +
				"jobs: [" + this.join(this.jobs, ", ") + "]" +
		"}";
	}

	@Extension
	public static final class DescriptorImpl extends ViewDescriptor {
		@Override
		public String getDisplayName() {
			return "Pipeline Dashboard";
		}
	}
}

