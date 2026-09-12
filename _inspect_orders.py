import sqlite3, os, datetime
db = os.path.join(os.environ['TEMP'], 'g2.db')
con = sqlite3.connect(db); con.row_factory = sqlite3.Row; cur = con.cursor()

def bstart(d): return int(datetime.datetime.combine(d, datetime.time(3,0)).timestamp()*1000)
def bend(d): return int((datetime.datetime.combine(d, datetime.time(3,0))+datetime.timedelta(days=1)).timestamp()*1000)

q = ("SELECT bill_number,status FROM orders o WHERE COALESCE(o.finalized_at,o.cancelled_at,o.updated_at)>=? "
     "AND COALESCE(o.finalized_at,o.cancelled_at,o.updated_at)<? AND o.status IN ('paid','due','cancelled') "
     "ORDER BY COALESCE(o.finalized_at,o.cancelled_at,o.updated_at) DESC")

for label, d in [("Sept 10 business day (Sep10 03:00 -> Sep11 03:00)", datetime.date(2026,9,10)),
                 ("Sept 11 business day (Sep11 03:00 -> Sep12 03:00)", datetime.date(2026,9,11))]:
    rows = cur.execute(q,(bstart(d),bend(d))).fetchall()
    print(f"{label}: {len(rows)} bills -> {[r['bill_number'] for r in rows]}")
con.close()
