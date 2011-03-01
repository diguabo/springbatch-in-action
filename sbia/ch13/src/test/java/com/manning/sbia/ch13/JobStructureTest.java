/**
 * 
 */
package com.manning.sbia.ch13;

import java.util.Date;

import javax.sql.DataSource;

import org.junit.Test;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;

/**
 * @author templth
 *
 */
@ContextConfiguration({
	"classpath:/com/manning/sbia/ch13/batch-infrastructure-context.xml",
	"classpath:/com/manning/sbia/ch13/JobStructureTest-context.xml"})
public class JobStructureTest extends AbstractJobStructureTest {

	@Autowired
	private DataSource dataSource;
	
	@Test public void delimitedJob() throws Exception {
		JobParametersBuilder parametersBuilder = new JobParametersBuilder();
		parametersBuilder.addDate("date", new Date());
		jobLauncher.run(jobSuccess, parametersBuilder.toJobParameters());
	}
	
	
}
