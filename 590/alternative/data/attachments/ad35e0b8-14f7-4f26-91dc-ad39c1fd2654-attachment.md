## DELETE http://localhost:44401/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sun, 12 Apr 2026 18:55:09 GMT
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
curl 'http://localhost:44401/echo' \
  -X DELETE
```
