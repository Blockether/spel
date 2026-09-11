## DELETE http://localhost:38189/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 11 Sep 2026 18:16:45 GMT
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
curl 'http://localhost:38189/echo' \
  -X DELETE
```
