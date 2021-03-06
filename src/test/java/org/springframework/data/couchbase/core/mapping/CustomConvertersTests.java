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

package org.springframework.data.couchbase.core.mapping;

import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.couchbase.UnitTestApplicationConfig;
import org.springframework.data.couchbase.core.convert.CouchbaseCustomConversions;
import org.springframework.data.couchbase.core.convert.MappingCouchbaseConverter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.couchbase.client.java.repository.annotation.Field;
import com.couchbase.client.java.repository.annotation.Id;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests to verify custom mapping logic.
 *
 * @author Michael Nitschinger
 * @author Mark Paluch
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = UnitTestApplicationConfig.class)
public class CustomConvertersTests {

  @Autowired
  private MappingCouchbaseConverter converter;

  @After
  public void cleanup() {
    converter.setCustomConversions(new CouchbaseCustomConversions(Collections.emptyList()));
  }

  @Test
  public void shouldWriteWithCustomConverter() {
    List<Object> converters = new ArrayList<Object>();
    converters.add(DateToStringConverter.INSTANCE);
    converter.setCustomConversions(new CouchbaseCustomConversions(converters));
    converter.afterPropertiesSet();

    Date date = new Date();
    BlogPost post = new BlogPost();
    post.created = date;

    CouchbaseDocument doc = new CouchbaseDocument();
    converter.write(post, doc);

    assertThat(doc.getPayload().get("created")).isEqualTo(date.toString());
  }

  @Test
  public void shouldReadWithCustomConverter() {
    List<Object> converters = new ArrayList<Object>();
    converters.add(IntegerToStringConverter.INSTANCE);
    converter.setCustomConversions(new CouchbaseCustomConversions(converters));
    converter.afterPropertiesSet();

    CouchbaseDocument doc = new CouchbaseDocument();
    doc.getPayload().put("content", 10);
    Counter loaded = converter.read(Counter.class, doc);
    assertThat(loaded.content).isEqualTo("even");
  }

  @Test
  public void shouldWriteConvertFullDocument() {
    List<Object> converters = new ArrayList<Object>();
    converters.add(BlogPostToCouchbaseDocumentConverter.INSTANCE);
    converter.setCustomConversions(new CouchbaseCustomConversions(converters));
    converter.afterPropertiesSet();

    BlogPost post = new BlogPost();
    post.id = "foobar";
    post.title = "The Foo of the Bar";

    CouchbaseDocument doc = new CouchbaseDocument();
    converter.write(post, doc);

    assertThat(doc.getPayload().get("title")).isEqualTo("The Foo of the Bar");
    assertThat(doc.getPayload().get("slug")).isEqualTo("the_foo_of_the_bar");
  }

  @Test
  public void shouldReadConvertFullDocument() {
    List<Object> converters = new ArrayList<Object>();
    converters.add(CouchbaseDocumentToBlogPostConverter.INSTANCE);
    converter.setCustomConversions(new CouchbaseCustomConversions(converters));
    converter.afterPropertiesSet();

    CouchbaseDocument doc = new CouchbaseDocument();
    doc.getPayload().put("title", "My Title");

    BlogPost loaded = converter.read(BlogPost.class, doc);
    assertThat(loaded.id).isEqualTo("modified");
    assertThat(loaded.title).isEqualTo("My Title!!");
  }

  public static class BlogPost {
    @Id //also tests DATACOUCH-145 (this is SDK's @Id)
    public String id = "key";

    @Field
    public Date created;

    @Field
    public String title;

  }

  public class Counter {
    @Field
    public String content;
  }

  public static enum IntegerToStringConverter implements Converter<Integer, String> {
    INSTANCE;

    @Override
    public String convert(Integer source) {
      return source % 2 == 0 ? "even" : "odd";
    }
  }

  public static enum DateToStringConverter implements Converter<Date, String> {
    INSTANCE;

    public static Format FORMATTER = new SimpleDateFormat("yyyy HH");

    @Override
    public String convert(Date source) {
      return source.toString();
    }
  }

  @WritingConverter
  public static enum BlogPostToCouchbaseDocumentConverter implements Converter<BlogPost, CouchbaseDocument> {
    INSTANCE;

    @Override
    public CouchbaseDocument convert(BlogPost source) {
      return new CouchbaseDocument()
          .setId(source.id)
          .put("title", source.title)
          .put("slug", source.title.toLowerCase().replaceAll(" ", "_"));
    }
  }

  @ReadingConverter
  public static enum CouchbaseDocumentToBlogPostConverter implements Converter<CouchbaseDocument, BlogPost> {
    INSTANCE;

    @Override
    public BlogPost convert(CouchbaseDocument source) {
      BlogPost post = new BlogPost();
      post.id = "modified";
      post.title = source.getPayload().get("title") + "!!";
      return post;
    }
  }

}
