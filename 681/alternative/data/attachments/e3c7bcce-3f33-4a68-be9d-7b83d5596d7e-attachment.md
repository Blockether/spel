## PUT http://localhost:46553/echo → 200 OK

### Request Body
```json
{
  "name": "Eve Updated"
}
```

### Response Headers
```
content-length: 61
content-type: application/json
date: Tue, 04 Aug 2026 13:22:29 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Eve Updated"
  }
}
```

### cURL
```bash
curl 'http://localhost:46553/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
