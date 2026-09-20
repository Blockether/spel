## DELETE http://localhost:35345/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sun, 20 Sep 2026 13:27:32 GMT
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
curl 'http://localhost:35345/echo' \
  -X DELETE
```
