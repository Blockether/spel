## DELETE http://localhost:42613/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Wed, 23 Sep 2026 12:25:49 GMT
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
curl 'http://localhost:42613/echo' \
  -X DELETE
```
