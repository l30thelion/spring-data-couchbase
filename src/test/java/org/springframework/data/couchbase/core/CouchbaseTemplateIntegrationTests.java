/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.data.couchbase.core;

import static com.couchbase.client.java.query.Select.select;
import static com.couchbase.client.java.query.dsl.Expression.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.document.RawJsonDocument;
import com.couchbase.client.java.error.DocumentDoesNotExistException;
import com.couchbase.client.java.query.N1qlParams;
import com.couchbase.client.java.query.N1qlQuery;
import com.couchbase.client.java.query.N1qlQueryResult;
import com.couchbase.client.java.query.consistency.ScanConsistency;
import com.couchbase.client.java.repository.annotation.Field;
import com.couchbase.client.java.view.Stale;
import com.couchbase.client.java.view.ViewQuery;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
;
import org.springframework.data.couchbase.ContainerResourceRunner;
import org.springframework.data.couchbase.IntegrationTestApplicationConfig;
import org.springframework.data.couchbase.core.convert.MappingCouchbaseConverter;
import org.springframework.data.couchbase.core.mapping.Document;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;

/**
 * @author Michael Nitschinger
 * @author Simon Baslé
 * @author Anastasiia Smirnova
 */
@RunWith(ContainerResourceRunner.class)
@ContextConfiguration(classes = IntegrationTestApplicationConfig.class)
@TestExecutionListeners(CouchbaseTemplateQueryListener.class)
public class CouchbaseTemplateIntegrationTests {
	@Rule
	public TestName testName = new TestName();

	@Autowired
	private Bucket client;

	@Autowired
	private CouchbaseTemplate template;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private void removeIfExist(String key) {
		try {
			template.remove(key);
		}
		catch (DocumentDoesNotExistException e) {
			//ignore
		}
	}

	@Test
	public void saveSimpleEntityCorrectly() throws Exception {
		String id = "beers:awesome-stout";
		removeIfExist(id);

		String name = "The Awesome Stout";
		boolean active = false;
		Beer beer = new Beer(id, name, active, "");

		template.save(beer);
		RawJsonDocument resultDoc = client.get(id, RawJsonDocument.class);
		assertThat(resultDoc).isNotNull();
		String result = resultDoc.content();
		assertThat(result).isNotNull();
		Map<String, Object> resultConv = MAPPER.readValue(result, new TypeReference<Map<String, Object>>() {});

		assertThat(resultConv.get(MappingCouchbaseConverter.TYPEKEY_DEFAULT)).isNotNull();
		assertThat(resultConv.get("javaClass")).isNull();
		assertThat(resultConv.get(MappingCouchbaseConverter.TYPEKEY_DEFAULT))
				.isEqualTo("org.springframework.data.couchbase.core.Beer");
		assertThat(resultConv.get("is_active")).isEqualTo(false);
		assertThat(resultConv.get("name")).isEqualTo("The Awesome Stout");
	}

	@Test
	public void saveDocumentWithExpiry() throws Exception {
		String id = "simple-doc-with-expiry";
		DocumentWithExpiry doc = new DocumentWithExpiry(id);
		template.save(doc);
		assertThat(client.get(id)).isNotNull();
		Thread.sleep(3000);
		assertThat(client.get(id)).isNull();
	}

	@Test
	public void insertDoesNotOverride() throws Exception {
		String id = "double-insert-test";
		removeIfExist(id);

		SimplePerson doc = new SimplePerson(id, "Mr. A");
		template.insert(doc);
		RawJsonDocument resultDoc = client.get(id, RawJsonDocument.class);
		assertThat(resultDoc).isNotNull();
		String result = resultDoc.content();

		Map<String, String> resultConv = MAPPER.readValue(result, new TypeReference<Map<String, String>>() {});
		assertThat(resultConv.get("name")).isEqualTo("Mr. A");

		doc = new SimplePerson(id, "Mr. B");
		try {
			template.insert(doc);
		} catch (OptimisticLockingFailureException e) {
			//ignore, since this insert should fail
		}

		resultDoc = client.get(id, RawJsonDocument.class);
		assertThat(resultDoc).isNotNull();
		result = resultDoc.content();

		resultConv = MAPPER.readValue(result, new TypeReference<Map<String, String>>() {});
		assertThat(resultConv.get("name")).isEqualTo("Mr. A");
	}


	@Test
	public void updateDoesNotInsert() {
		String id = "update-does-not-insert";
		SimplePerson doc = new SimplePerson(id, "Nice Guy");
		template.update(doc);
		assertThat(client.get(id)).isNull();
	}


	@Test
	public void removeDocument() {
		String id = "beers:to-delete-stout";
		Beer beer = new Beer(id, "", false, "");

		template.save(beer);
		Object result = client.get(id);
		assertThat(result).isNotNull();

		template.remove(beer);
		result = client.get(id);
		assertThat(result).isNull();
	}


	@Test
	public void storeListsAndMaps() {
		String id = "persons:lots-of-names";
		List<String> names = new ArrayList<String>();
		names.add("Michael");
		names.add("Thomas");
		names.add(null);
		List<Integer> votes = new LinkedList<Integer>();
		Map<String, Boolean> info1 = new HashMap<String, Boolean>();
		info1.put("foo", true);
		info1.put("bar", false);
		info1.put("nullValue", null);
		Map<String, Integer> info2 = new HashMap<String, Integer>();

		ComplexPerson complex = new ComplexPerson(id, names, votes, info1, info2);

		template.save(complex);
		assertThat(client.get(id)).isNotNull();

		ComplexPerson response = template.findById(id, ComplexPerson.class);
		assertThat(response.getFirstnames()).isEqualTo(names);
		assertThat(response.getVotes()).isEqualTo(votes);
		assertThat(response.getId()).isEqualTo(id);
		assertThat(response.getInfo1()).isEqualTo(info1);
		assertThat(response.getInfo2()).isEqualTo(info2);
	}


	@Test
	public void validFindById() {
		String id = "beers:findme-stout";
		String name = "The Findme Stout";
		boolean active = true;
		Beer beer = new Beer(id, name, active, "");
		template.save(beer);

		Beer found = template.findById(id, Beer.class);

		assertThat(found).isNotNull();
		assertThat(found.getId()).isEqualTo(id);
		assertThat(found.getName()).isEqualTo(name);
		assertThat(found.getActive()).isEqualTo(active);
	}

	@Test
	public void shouldLoadAndMapViewDocs() {
		ViewQuery query = ViewQuery.from("test_beers", "by_name");
		query.stale(Stale.FALSE);

		final List<Beer> beers = template.findByView(query, Beer.class);
		assertThat(beers.size() > 0).isTrue();

		for (Beer beer : beers) {
			assertThat(beer.getId()).isNotNull();
			assertThat(beer.getName()).isNotNull();
			assertThat(beer.getActive()).isNotNull();
		}
	}

	@Test
	public void shouldQueryRaw() {
		N1qlQuery query = N1qlQuery.simple(select("name").from(i(client.name()))
				.where(x("name").isNotMissing()));

		N1qlQueryResult queryResult = template.queryN1QL(query);
		assertThat(queryResult).isNotNull();
		assertThat(queryResult.finalSuccess()).as(queryResult.errors().toString())
				.isTrue();
		assertThat(queryResult.allRows().isEmpty()).isFalse();
	}

	@Test
	public void shouldQueryWithMapping() {
		FullFragment ff1 = new FullFragment("fullFragment1", 1, "fullFragment", "test1");
		FullFragment ff2 = new FullFragment("fullFragment2", 2, "fullFragment", "test2");
		template.save(Arrays.asList(ff1, ff2));

		N1qlQuery query = N1qlQuery.simple(select(i("value")) //"value" is a n1ql keyword apparently
						.from(i(client.name()))
						.where(x("type").eq(s("fullFragment"))
								.and(x("criteria").gt(1))),

				N1qlParams.build().consistency(ScanConsistency.REQUEST_PLUS));

		List<Fragment> fragments = template.findByN1QLProjection(query, Fragment.class);
		assertThat(fragments).isNotNull();
		assertThat(fragments.isEmpty()).isFalse();
		assertThat(fragments.size()).isEqualTo(1);
		assertThat(fragments.get(0).value).isEqualTo("test2");
	}

	/**
	 * @see DATACOUCH-159
	 */
	@Test
	public void shouldDeserialiseLongsAndInts() {
		final long longValue = new Date().getTime();
		final int intValue = new Random().nextInt();

		template.save(new SimpleWithLongAndInt("simpleWithLong:simple", longValue, intValue));
		SimpleWithLongAndInt document = template.findById("simpleWithLong:simple", SimpleWithLongAndInt.class);
		assertThat(document).isNotNull();
		assertThat(document.getLongValue()).isEqualTo(longValue);
		assertThat(document.getIntValue()).isEqualTo(intValue);

		template.save(new SimpleWithLongAndInt("simpleWithLong:simple:other", intValue, intValue));
		document = template.findById("simpleWithLong:simple:other", SimpleWithLongAndInt.class);
		assertThat(document).isNotNull();
		assertThat(document.getLongValue()).isEqualTo(intValue);
		assertThat(document.getIntValue()).isEqualTo(intValue);
	}

	@Test
	public void shouldDeserialiseEnums() {
		SimpleWithEnum simpleWithEnum = new SimpleWithEnum("simpleWithEnum:enum", SimpleWithEnum.Type.BIG);
		template.save(simpleWithEnum);
		simpleWithEnum = template.findById("simpleWithEnum:enum", SimpleWithEnum.class);
		assertThat(simpleWithEnum).isNotNull();
		assertThat(SimpleWithEnum.Type.BIG).isEqualTo(simpleWithEnum.getType());
	}

	@Test
	public void shouldDeserialiseClass() {
		SimpleWithClass simpleWithClass = new SimpleWithClass("simpleWithClass:class", Integer.class);
		simpleWithClass.setValue("The dish ran away with the spoon.");
		template.save(simpleWithClass);
		simpleWithClass = template.findById("simpleWithClass:class", SimpleWithClass.class);
		assertThat(simpleWithClass).isNotNull();
		assertThat(simpleWithClass.getValue())
				.isEqualTo("The dish ran away with the spoon.");
	}

	@Test
	public void shouldHandleCASVersionOnInsert() throws Exception {
		removeIfExist("versionedClass:1");

		VersionedClass versionedClass = new VersionedClass("versionedClass:1", "foobar");
		assertThat(versionedClass.getVersion()).isEqualTo(0);
		template.insert(versionedClass);
		RawJsonDocument rawStored = client.get("versionedClass:1", RawJsonDocument.class);
		assertThat(versionedClass.getVersion()).isEqualTo(rawStored.cas());
	}

	@Test
	public void versionShouldNotUpdateOnSecondInsert() throws Exception {
		removeIfExist("versionedClass:2");

		VersionedClass versionedClass = new VersionedClass("versionedClass:2", "foobar");
		template.insert(versionedClass);
		long version1 = versionedClass.getVersion();
		try {
			template.insert(versionedClass);
		} catch (OptimisticLockingFailureException e) {
			//ignore, since this insert should fail
		}
		long version2 = versionedClass.getVersion();

		assertThat(version1 > 0).isTrue();
		assertThat(version2 > 0).isTrue();
		assertThat(version2).isEqualTo(version1);
	}

	@Test
	public void shouldSaveDocumentOnMatchingVersion() throws Exception {
		removeIfExist("versionedClass:3");

		VersionedClass versionedClass = new VersionedClass("versionedClass:3", "foobar");
		template.insert(versionedClass);
		long version1 = versionedClass.getVersion();

		versionedClass.setField("foobar2");
		template.save(versionedClass);
		long version2 = versionedClass.getVersion();

		assertThat(version1 > 0).isTrue();
		assertThat(version2 > 0).isTrue();
		assertThat(version2).isNotEqualTo(version1);

		assertThat(template.findById("versionedClass:3", VersionedClass.class).getField())
				.isEqualTo("foobar2");
	}

	@Test(expected = OptimisticLockingFailureException.class)
	public void shouldNotSaveDocumentOnNotMatchingVersion() throws Exception {
		removeIfExist("versionedClass:4");

		VersionedClass versionedClass = new VersionedClass("versionedClass:4", "foobar");
		template.insert(versionedClass);

		RawJsonDocument toCompare = RawJsonDocument.create("versionedClass:4", "different");
		assertThat(client.upsert(toCompare)).isNotNull();

		versionedClass.setField("foobar2");
		//save (aka upsert) won't error in case of CAS mismatch anymore
		template.update(versionedClass);
	}

	@Test
	public void shouldUpdateDocumentOnMatchingVersion() throws Exception {
		removeIfExist("versionedClass:5");

		VersionedClass versionedClass = new VersionedClass("versionedClass:5", "foobar");
		template.insert(versionedClass);
		long version1 = versionedClass.getVersion();

		versionedClass.setField("foobar2");
		template.update(versionedClass);
		long version2 = versionedClass.getVersion();

		assertThat(version1 > 0).isTrue();
		assertThat(version2 > 0).isTrue();
		assertThat(version2).isNotEqualTo(version1);

		assertThat(template.findById("versionedClass:5", VersionedClass.class).getField())
				.isEqualTo("foobar2");
	}

	@Test(expected = OptimisticLockingFailureException.class)
	public void shouldNotUpdateDocumentOnNotMatchingVersion() throws Exception {
		removeIfExist("versionedClass:6");

		VersionedClass versionedClass = new VersionedClass("versionedClass:6", "foobar");
		template.insert(versionedClass);

		RawJsonDocument toCompare = RawJsonDocument.create("versionedClass:6", "different");
		assertThat(client.upsert(toCompare)).isNotNull();

		versionedClass.setField("foobar2");
		template.update(versionedClass);
	}

	@Test
	public void shouldLoadVersionPropertyOnFind() throws Exception {
		removeIfExist("versionedClass:7");

		VersionedClass versionedClass = new VersionedClass("versionedClass:7", "foobar");
		template.insert(versionedClass);
		assertThat(versionedClass.getVersion() > 0).isTrue();

		VersionedClass foundClass = template.findById("versionedClass:7", VersionedClass.class);
		assertThat(foundClass.getVersion()).isEqualTo(versionedClass.getVersion());
	}

	@Test
	public void shouldUpdateAlreadyExistingDocument() throws Exception {
		final String key = testName.getMethodName();
		removeIfExist(key);

		final AtomicLong counter = new AtomicLong();

		VersionedClass initial = new VersionedClass(key, "value-0");
		template.save(initial);

		AsyncUtils.executeConcurrently(3, new Callable<Void>() {
			@Override
			public Void call() throws Exception {
				boolean saved = false;
				while(!saved) {
					long counterValue = counter.incrementAndGet();
					VersionedClass messageData = template.findById(key, VersionedClass.class);
					messageData.field = "value-" + counterValue;
					try {
						template.save(messageData);
						saved = true;
					} catch (OptimisticLockingFailureException e) {
					}
				}
				return null;
			}
		});

		VersionedClass actual = template.findById(key, VersionedClass.class);

		assertThat(actual.field).isNotEqualTo(initial.field);
		assertThat(actual.version).isNotEqualTo(initial.version);
	}

	@Test
	public void shouldInsertOnlyFirstDocumentAndNextAttemptsShouldFailWithOptimisticLockingException() throws Exception {
		final String key = testName.getMethodName();
		removeIfExist(key);

		final AtomicLong counter = new AtomicLong();
		final AtomicLong optimisticLockCounter = new AtomicLong();
		AsyncUtils.executeConcurrently(5, new Callable<Void>() {
			@Override
			public Void call() throws Exception {
				long counterValue = counter.incrementAndGet();
				String data = "value-" + counterValue;
				VersionedClass messageData = new VersionedClass(key, data);
				try {
					template.insert(messageData);
				} catch (OptimisticLockingFailureException e) {
					optimisticLockCounter.incrementAndGet();
				}
				//should save operation throw OptimisticLockingFailureException on next attempts to save?
				return null;
			}
		});


		assertThat(optimisticLockCounter.intValue()).isEqualTo(4);
	}

	/**
	 * @see DATACOUCH-59
   */
	@Test
	public void expiryWhenTouchOnReadDocument() throws InterruptedException {
		String id = "simple-doc-with-update-expiry-for-read";
		DocumentWithTouchOnRead doc = new DocumentWithTouchOnRead(id);
		template.save(doc);
		Thread.sleep(1000);
		assertThat(template.findById(id, DocumentWithTouchOnRead.class)).isNotNull();
		Thread.sleep(1000);
		assertThat(template.findById(id, DocumentWithTouchOnRead.class)).isNotNull();
		Thread.sleep(3000);
		assertThat(template.findById(id, DocumentWithTouchOnRead.class)).isNull();
	}

	/**
	 * @see DATACOUCH-227
	 */
	@Test
	public void shouldRetainOrderWhenQueryingViewOrdered() {
		ViewQuery q = ViewQuery.from("test_beers", "by_name");
		q.descending().includeDocsOrdered(true);

		String prev = null;
		List<Beer> beers = template.findByView(q, Beer.class);
		assertThat(q.isIncludeDocs()).isTrue();
		assertThat(q.isOrderRetained()).isTrue();
		assertThat(q.includeDocsTarget()).isEqualTo(RawJsonDocument.class);
		for (Beer beer : beers) {
			if (prev != null) {
				assertThat(beer.getName().compareTo(prev) < 0).describedAs(beer.getName() + " not alphabetically < to " + prev).isTrue();
			}
			prev = beer.getName();
		}
	}

	/**
	 * A sample document with just an id and property.
	 */
	@Document
	static class SimplePerson {

		@Id
		private final String id;
		@Field
		private final String name;

		public SimplePerson(String id, String name) {
			this.id = id;
			this.name = name;
		}
	}

	/**
	 * A sample document that expires in 2 seconds.
	 */
	@Document(expiry = 2)
	static class DocumentWithExpiry {

		@Id
		private final String id;

		public DocumentWithExpiry(String id) {
			this.id = id;
		}
	}

	/**
	 * A sample document that expires in 2 seconds and touchOnRead set.
	 */
	@Document(expiry = 2, touchOnRead = true)
	static class DocumentWithTouchOnRead {

		@Id
		private final String id;

		public DocumentWithTouchOnRead(String id) {
			this.id = id;
		}
	}

	@Document
	static class ComplexPerson {

		@Id
		private final String id;
		@Field
		private final List<String> firstnames;
		@Field
		private final List<Integer> votes;

		@Field
		private final Map<String, Boolean> info1;
		@Field
		private final Map<String, Integer> info2;

		public ComplexPerson(String id, List<String> firstnames,
							 List<Integer> votes, Map<String, Boolean> info1,
							 Map<String, Integer> info2) {
			this.id = id;
			this.firstnames = firstnames;
			this.votes = votes;
			this.info1 = info1;
			this.info2 = info2;
		}

		List<String> getFirstnames() {
			return firstnames;
		}

		List<Integer> getVotes() {
			return votes;
		}

		Map<String, Boolean> getInfo1() {
			return info1;
		}

		Map<String, Integer> getInfo2() {
			return info2;
		}

		String getId() {
			return id;
		}
	}

	@Document
	static class SimpleWithLongAndInt {

		@Id
		private String id;

		private long longValue;
		private int intValue;

		SimpleWithLongAndInt(final String id, final long longValue, int intValue) {
			this.id = id;
			this.longValue = longValue;
			this.intValue = intValue;
		}

		String getId() {
			return id;
		}

		long getLongValue() {
			return longValue;
		}

		void setLongValue(final long value) {
			this.longValue = value;
		}

		public int getIntValue() {
			return intValue;
		}

		public void setIntValue(int intValue) {
			this.intValue = intValue;
		}
	}

	static class SimpleWithEnum {

		@Id
		private String id;

		private enum Type {
			BIG
		}

		private Type type;

		SimpleWithEnum(final String id, final Type type) {
			this.id = id;
			this.type = type;
		}

		String getId() {
			return id;
		}

		void setId(final String id) {
			this.id = id;
		}

		Type getType() {
			return type;
		}

		void setType(final Type type) {
			this.type = type;
		}
	}

	static class SimpleWithClass {

		@Id
		private String id;

		private Class<Integer> integerClass;

		private String value;

		SimpleWithClass(final String id, final Class<Integer> integerClass) {
			this.id = id;
			this.integerClass = integerClass;
		}

		String getId() {
			return id;
		}

		void setId(final String id) {
			this.id = id;
		}

		Class<Integer> getIntegerClass() {
			return integerClass;
		}

		void setIntegerClass(final Class<Integer> integerClass) {
			this.integerClass = integerClass;
		}

		String getValue() {
			return value;
		}

		void setValue(final String value) {
			this.value = value;
		}
	}

	static class VersionedClass {

		@Id
		private String id;

		@Version
		private long version;

		private String field;

		VersionedClass(String id, String field) {
			this.id = id;
			this.field = field;
		}

		public String getId() {
			return id;
		}

		public long getVersion() {
			return version;
		}

		public String getField() {
			return field;
		}

		public void setField(String field) {
			this.field = field;
		}

		@Override
		public String toString() {
			return "VersionedClass{" +
					"id='" + id + '\'' +
					", version=" + version +
					", field='" + field + '\'' +
					'}';
		}
	}

	@Document
	static class FullFragment {

		@Id
		private String id;

		private long criteria;

		private String type;

		private String value;

		public FullFragment(String id, long criteria, String type, String value) {
			this.id = id;
			this.criteria = criteria;
			this.type = type;
			this.value = value;
		}

		public String getId() {
			return id;
		}

		public long getCriteria() {
			return criteria;
		}

		public String getType() {
			return type;
		}

		public String getValue() {
			return value;
		}

		public void setCriteria(long criteria) {
			this.criteria = criteria;
		}

		public void setType(String type) {
			this.type = type;
		}

		public void setValue(String value) {
			this.value = value;
		}
	}

	static class Fragment {
		public String value;
	}
}
