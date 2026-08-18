## PUT http://localhost:34611/echo → 200 OK

### Request Body
```json
{
  "name": "Alice Updated",
  "email": "alice2@example.org"
}
```

### Response Headers
```
content-length: 92
content-type: application/json
date: Tue, 18 Aug 2026 00:20:16 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Alice Updated",
    "email": "alice2@example.org"
  }
}
```

### cURL
```bash
curl 'http://localhost:34611/echo' \
  -X PUT \
  -d '{
  "name": "Alice Updated",
  "email": "alice2@example.org"
}'
```
