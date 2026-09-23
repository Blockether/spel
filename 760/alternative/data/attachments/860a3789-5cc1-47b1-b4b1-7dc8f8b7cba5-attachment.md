## DELETE http://localhost:33773/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Wed, 23 Sep 2026 11:27:04 GMT
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
curl 'http://localhost:33773/echo' \
  -X DELETE
```
