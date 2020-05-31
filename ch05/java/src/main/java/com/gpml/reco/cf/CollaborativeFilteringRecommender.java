package com.gpml.reco.cf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gpml.reco.util.FixedSizeOrderedList;
import com.gpml.vector.SparseVector;
import org.neo4j.driver.v1.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CollaborativeFilteringRecommender implements AutoCloseable {
    private final Driver driver;

    ObjectMapper objectMapper = new ObjectMapper();

    enum KNN_TYPE {
        USER,
        ITEM
    }

    public CollaborativeFilteringRecommender(String uri, String user, String password) {
        driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
    }

    private void computeAndStoreKNN(KNN_TYPE type) {
        switch (type) {
            case USER:
                computeAndStoreKNNForUsers(20);
                break;
            case ITEM:
                computeAndStoreKNNForItems(20);
                break;
        }
    }

    private void computeAndStoreKNNForUsers(int size) {
        Map<String, SparseVector> userVectors = getUserVectors();
        computeAndStoreKNN(userVectors, size, "User", "userId");
    }

    private void computeAndStoreKNNForItems(int size) {
        Map<String, SparseVector> vectors = getItemVectors();
        computeAndStoreKNN(vectors, size, "Item", "itemId");
    }

    private void computeAndStoreKNN(Map<String, SparseVector> vectors, int size, String label, String property) {
        vectors.entrySet().forEach(entry -> {
            FixedSizeOrderedList<SimilarityItem> knn = new FixedSizeOrderedList<>(size);
            vectors.entrySet().forEach(otherEntry -> {
                if (!entry.getKey().equalsIgnoreCase(otherEntry.getKey())) {
                    float cosinceSimilarity = SparseVector.getCosinceSimilarity(entry.getValue(), otherEntry.getValue());
                    if (cosinceSimilarity > 0) {
                        knn.add(new SimilarityItem(entry.getKey(), otherEntry.getKey(), cosinceSimilarity));
                    }
                }
            });
            storeKnn("User", "userId", entry.getKey(), knn);
        });
    }

    private Map<String, SparseVector> getUserVectors() {
        Map<String, SparseVector> userVectors = new HashMap<>();
        try (Session session = driver.session()) {
            Transaction tx = session.beginTransaction();
            List<String> userIds = getElements(tx, "User", "userId");
            userIds.forEach(userId -> {
                userVectors.put(userId, getUserSparseVector(tx, userId));
            });
        }
        return userVectors;
    }

    private Map<String, SparseVector> getItemVectors() {
        Map<String, SparseVector> userVectors = new HashMap<>();
        try (Session session = driver.session()) {
            Transaction tx = session.beginTransaction();
            List<String> itemIds = getElements(tx, "Item", "itemId");
            itemIds.forEach(itemId -> {
                userVectors.put(itemId, getItemSparseVector(tx, itemId));
            });
        }
        return userVectors;
    }

    private void storeKnn(String label, String property, String id, FixedSizeOrderedList<SimilarityItem> knn) {
        String deleteQuery = "MATCH (n:" + label + ")-[s:SIMILARITY]->() " +
                "WHERE n." + property + " = {id} " +
                "DELETE s";

        String query = "MATCH (n:" + label + ") " +
                "WHERE n." + property + " = {id} " +
                "UNWIND {sims} as sim " +
                "MATCH (o:" + label + ") " +
                "WHERE o." + property + " = sim.secondNode " +
                "CREATE (n)-[s:SIMILARITY {value: toFloat(sim.similarity)   }]->(o)";
        List<Map<String, Object>> sims =
                knn.stream().map(item -> (Map<String, Object>) objectMapper.convertValue(item, Map.class))
                        .collect(Collectors.toList());


        try (Session session = driver.session()) {
            Transaction tx = session.beginTransaction();
            Map<String, Object> params = new HashMap<>();
            params.put("id", id);
            params.put("sims", sims);

            tx.run(deleteQuery, params);
            tx.run(query, params);
            tx.success();
        }
    }

    private SparseVector getUserSparseVector(Transaction tx, String userId) {
        String query = "MATCH (u:User {userId: {id}})-[:BUYS]->(i:Item)\n" +
                "return id(i) as index, 1.0 as value\n" +
                "order by index\n";
        return getSparseVector(tx, userId, query);
    }

    private SparseVector getItemSparseVector(Transaction tx, String itemId) {
        String query = "MATCH (u:User )-[:BUYS]->(i:Item {itemId: {id}})\n" +
                "return id(u) as index, 1.0 as value\n" +
                "order by index\n";
        return getSparseVector(tx, itemId, query);
    }

    private SparseVector getSparseVector(Transaction tx, String id, String query) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        StatementResult result = tx.run(query, params);
        Map<Long, Float> vector = new HashMap<>();
        while (result.hasNext()) {
            Record next = result.next();
            vector.put(next.get(0).asLong(), next.get(0).asFloat());
        }
        return SparseVector.fromMap(vector);
    }

    private List<String> getElements(Transaction tx, String label, String property) {
        String query = "MATCH (u:" + label + ") RETURN u." + property + " as id";
        StatementResult result = tx.run(query);
        List<String> userIds = new ArrayList<>();
        while (result.hasNext()) {
            Record next = result.next();
            userIds.add(next.get(0).asString());
        }
        return userIds;
    }


    private List<RecommendationElement> getRecommendationsForUser(String userId, int size, KNN_TYPE type) {
        switch (type) {
            case USER:
                return getRecommendationsUserBased(userId, size);
            case ITEM:
                return getRecommendationsItemBased(userId, size);
            default:
                throw new RuntimeException("Type not supported");
        }
    }

    private List<RecommendationElement> getRecommendationsItemBased(String userId, int size) {
        List<String> notSeenYetItemList = getNotSeenYetItemList(userId);
        FixedSizeOrderedList<RecommendationElement> recommendations = new FixedSizeOrderedList<>(size);
        notSeenYetItemList.stream().forEach(item -> {
            float score = computeScoreItemBased(userId, item);
            recommendations.add(new RecommendationElement(item, score));
        });
        return recommendations;
    }

    private float computeScoreItemBased(String userId, String itemId) {
        String query = "MATCH (user:User)-[:BOUGHT]->(item:Item)-[r:SIMILAR]->(target:Item)" +
                "WHERE user.userId = {userId} AND target.itemId = {targetId}\n" +
                "return sum(r.value) as score";
        return getScore(userId, itemId, query);
    }

    private float getScore(String userId, String itemId, String query) {
        try (Session session = driver.session()) {
            Transaction tx = session.beginTransaction();
            Map<String, Object> params = new HashMap<>();
            params.put("userId", userId);
            params.put("itemId", itemId);
            StatementResult result = tx.run(query, params);
            float score = 0.0f;
            while (result.hasNext()) {
                Record next = result.next();
                score = next.get(0).asFloat();
            }
            return score;
        }
    }


    private List<RecommendationElement> getRecommendationsUserBased(String userId, int size) {
        List<String> notSeenYetItemList = getNotSeenYetItemList(userId);
        FixedSizeOrderedList<RecommendationElement> recommendations = new FixedSizeOrderedList<>(size);
        notSeenYetItemList.stream().forEach(item -> {
            float score = computeScoreUserBased(userId, item);
            recommendations.add(new RecommendationElement(item, score));
        });
        return recommendations;
    }

    private float computeScoreUserBased(String userId, String itemId) {
        String query = "MATCH (user:User)-[:SIMILAR]->(otherUser:User)\n" +
                "WHERE user.userId = {userId}\n" +
                "WITH otherUser, count(otherUser) as size\n" +
                "MATCH (otherUser)-[r:BOUGHT]->(target:Target)\n" +
                "WHERE target.itemId = {targetId}\n" +
                "return (1.0f/size)*count(r) as score";
        return getScore(userId, itemId, query);
    }

    private List<String> getNotSeenYetItemList(String userId) {
        String query = "MATCH (user:User {userId:{userId}})\n" +
                "WITH user\n" +
                "MATCH (item:Item)\n" +
                "WHERE NOT EXISTS((user)-[:BOUGHT]->(item))\n" +
                "return item.itemId";
        List<String> items = new ArrayList<>();
        try (Session session = driver.session()) {
            Transaction tx = session.beginTransaction();
            Map<String, Object> params = new HashMap<>();
            params.put("userId", userId);
            StatementResult result = tx.run(query, params);
            while (result.hasNext()) {
                Record next = result.next();
                items.add(next.get(0).asString());
            }
        }
        return items;
    }

    public static void main(String[] args) throws Exception {
        try (CollaborativeFilteringRecommender recommender = new CollaborativeFilteringRecommender("bolt://localhost:7687", "neo4j", "pippo1")) {
            recommender.computeAndStoreKNN(KNN_TYPE.USER);
            recommender.computeAndStoreKNN(KNN_TYPE.ITEM);
            List<RecommendationElement> recommendations = recommender.getRecommendationsForUser("userId", 10, KNN_TYPE.USER);
            recommendations.forEach(item -> {
                System.out.println(item.getOtherNode());
            });
            recommendations = recommender.getRecommendationsForUser("userId", 10, KNN_TYPE.ITEM);
            recommendations.forEach(item -> {
                System.out.println(item.getOtherNode());
            });
        }
    }


    @Override
    public void close() throws Exception {
        driver.close();
    }
}