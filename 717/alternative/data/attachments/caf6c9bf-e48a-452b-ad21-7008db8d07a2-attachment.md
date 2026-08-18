## DELETE http://localhost:33461/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 18 Aug 2026 08:15:47 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:33461/echo' \
  -X DELETE
```
