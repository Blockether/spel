## DELETE http://localhost:40379/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Tue, 15 Sep 2026 13:25:24 GMT
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
curl 'http://localhost:40379/echo' \
  -X DELETE
```
