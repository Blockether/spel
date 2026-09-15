## PUT http://localhost:43907/echo → 200 OK

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
date: Tue, 15 Sep 2026 11:16:47 GMT
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
curl 'http://localhost:43907/echo' \
  -X PUT \
  -d '{
  "name": "Alice Updated",
  "email": "alice2@example.org"
}'
```
