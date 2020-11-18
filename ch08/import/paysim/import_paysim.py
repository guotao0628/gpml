import pandas as pd
import numpy as np
import time
import operator
from neo4j import GraphDatabase


class PaySimImporter(object):

    def __init__(self, uri, user, password):
        self._driver = GraphDatabase.driver(uri, auth=(user, password))

    def close(self):
        self._driver.close()

    def import_paysim(self, file):
        dtype = {
            "step": np.int64,
            "type": np.object,
            "amount": np.float32,
            "nameOrig": np.object,
            "oldbalanceOrg": np.float32,
            "newbalanceOrig": np.float32,
            "nameDest": np.object,
            "oldbalanceDest": np.float32,
            "newbalanceDest": np.float32,
            "isFraud": np.int32,
            "isFlaggedFraud": np.int32
        }

        j = 0;
        transaction_by_user = {}
        for chunk in pd.read_csv(file,
                                 header=0,
                                 dtype=dtype,
                                 names=['step', 'type', 'amount', 'nameOrig', 'oldbalanceOrg', 'newbalanceOrig',
                                        'nameDest', 'oldbalanceDest', 'newbalanceDest', 'isFraud', 'isFlaggedFraud'],
                                 chunksize=10 ** 3):
            df = chunk
            for record in df.to_dict("records"):
                row = record.copy()
                j += 1
                row["sourceLabels"] = ["Customer"]
                row["destLabels"] = []
                row["transLabels"] = []
                row["relationshipType"] = row["type"].upper()
                if row["nameDest"].startswith("M"):
                    row["destLabels"] += ["Merchant"]
                else:
                    row["destLabels"] += ["Customer"]
                if row["isFraud"] == 1:
                    row["transLabels"] += ["Fraud"]
                userId = row["nameOrig"]
                if userId in transaction_by_user:
                    transaction_by_user[userId] += [row]
                else:
                    transaction_by_user[userId] = [row]
                if j % 1000 == 0:
                    print(j, "lines processed")
            print(j, "lines processed")
        print(j, "total lines")
        print("total number of users", len(transaction_by_user))

        print("total number of users after filtering", len(transaction_by_user))
        query = """
            WITH $row as map
            MERGE (source:Entity {id: map.nameOrig})
            MERGE (dest:Entity {id: map.nameDest})
            WITH source, dest, map 
            CALL apoc.create.addLabels( dest, map.destLabels) YIELD node as destNode
            CALL apoc.create.addLabels( source, map.sourceLabels) YIELD node as sourceNode
            WITH source, dest, map
            CREATE (transaction:Transaction {id: $transId})
            SET transaction += map
            WITH transaction, source, dest, map
            CALL apoc.create.addLabels( transaction, map.transLabels) YIELD node
            CREATE (source)<-[:TRANSACTION_SOURCE]-(transaction)
            CREATE (dest)<-[:TRANSACTION_DEST]-(transaction)
            CREATE (source)-[t:TRANSFER_MONEY_TO]->(dest)
            SET t = map
            WITH source, dest, map
            CALL apoc.create.relationship(source, map.relationshipType, map, dest) YIELD rel
            RETURN map
        """

        with self._driver.session() as session:
            session.run("CREATE CONSTRAINT ON (s:Entity) ASSERT s.id IS UNIQUE")
            session.run("CREATE CONSTRAINT ON (s:Customer) ASSERT s.id IS UNIQUE")
            session.run("CREATE CONSTRAINT ON (s:Merchant) ASSERT s.id IS UNIQUE")
            session.run("CREATE CONSTRAINT ON (s:Transaction) ASSERT s.id IS UNIQUE")
            tx = session.begin_transaction()
            j = 0
            i = 0
            for user_id in list(transaction_by_user):
                for row in transaction_by_user[user_id]:
                    try:
                        tx.run(query, {"row": row, "transId": str(row['step']) + "_" + row['nameOrig'] + "_" + row['nameDest']})
                        i += 1
                        j += 1
                        if i == 2000:
                            tx.commit()
                            print(j, "lines processed")
                            i = 0
                            tx = session.begin_transaction()
                    except Exception as e:
                        print(e, row)
            try:
                if session.has_transaction():
                    tx.commit()
            except Exception as e:
                print(e)

    def post_processing(self, sess_clicks):
        print("start post processing")
        with self._driver.session() as session:
            tx = session.begin_transaction()
            i = 0
            j = 0
            post_processing_query = """
                MATCH (s:Session {sessionId: $sessionId})-[:CONTAINS]->(click)
                WITH s, click
                ORDER BY click.timestamp
                WITH s, collect(click) as clicks
                WITH s, clicks, clicks[size(clicks) - 1] as lastClick
                CREATE (s)-[:LAST_CLICK]->(lastClick)
                WITH s, clicks
                UNWIND range(1, size(clicks) - 1) as i
                WITH clicks[i - 1] as source, clicks[i] as dest
                CREATE (source)-[:NEXT]->(dest)
            """
            for session_id in list(sess_clicks):
                try:
                    tx.run(post_processing_query, {"sessionId": session_id})
                    i += 1
                    j += 1
                    if i == 2000:
                        tx.commit()
                        print(j, "lines processed")
                        i = 0
                        tx = session.begin_transaction()
                except Exception as e:
                    print(e, session_id)
            tx.commit()
            print(j, "sessions created processed")
        return sess_clicks


def strip(string): return ''.join([c if 0 < ord(c) < 128 else ' ' for c in string])


if __name__ == '__main__':
    uri = "bolt://localhost:7687"
    importer = PaySimImporter(uri=uri, user="neo4j", password="q1")

    start = time.time()
    file_path = "/Users/ale/neo4j-servers/gpml/dataset/paysim/PS_20174392719_1491204439457_log.csv"
    if (len(sys.argv) > 1):
        file_path = sys.argv[1]
    sessions = importer.import_paysim(file=file_path)
    print("Time to complete paysim ingestion:", time.time() - start)

    # intermediate = time.time()
    # importer.post_processing(sess_clicks=sessions)
    # print("Time to complete post processing:", time.time() - intermediate)

    print("Time to complete end-to-end:", time.time() - start)

    importer.close()
