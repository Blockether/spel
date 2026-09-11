## DELETE http://localhost:42191/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 11 Sep 2026 14:58:52 GMT
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
curl 'http://localhost:42191/echo' \
  -X DELETE
```
