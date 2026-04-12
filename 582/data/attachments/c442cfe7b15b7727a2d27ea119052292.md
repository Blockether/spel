## DELETE http://localhost:37309/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sun, 12 Apr 2026 09:39:29 GMT
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
curl 'http://localhost:37309/echo' \
  -X DELETE
```
