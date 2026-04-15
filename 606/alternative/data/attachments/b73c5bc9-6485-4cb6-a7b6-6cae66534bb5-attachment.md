## DELETE http://localhost:42367/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Wed, 15 Apr 2026 10:32:48 GMT
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
curl 'http://localhost:42367/echo' \
  -X DELETE
```
